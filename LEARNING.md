# 学习笔记

按“能讲清楚每一行为什么这么写”整理，不是 API 手册。代码位置相对 `FlashSaleBackend/src/main/java/com/ftllight/flashsalebackend/`。

## 1. 秒杀到底难在哪

1. **瞬时流量**：100 库存、上万请求，DB 直接打会被打死 → 用 Redis 前置判断。
2. **超卖**：读库存、判断、扣减三步如果不是原子的，并发下会多卖 → 用 Lua 一次执行。
3. **重复下单**：同一用户并发点两次 → 用 Redis `SET NX` 一人一单 + DB 唯一键兜底。
4. **削峰**：扣减成功后不立刻写库，投 MQ 让消费者按自己的节奏落库。
5. **最终一致**：Redis 扣减与 DB 落库之间可能失败 → 消费端重试 + 唯一键 + 补偿脚本。

## 2. Redis Lua 原子性

`service/Impl/SeckillServiceImpl.java` 中的 `SECKILL_SCRIPT`：

```lua
-- 1 售罄标记存在 → 返回 2
-- 2 库存 <= 0   → 写售罄标记，返回 3
-- 3 一人一单   → SET NX EX 1800 失败返回 4
-- 4 库存 -1    → 返回 1
```

要点：

- `EVAL` 期间 Redis 单线程执行，脚本内所有命令不会被其他请求插队，等价于一把锁。
- 不要在脚本外用 `GET` + `DECR` 模拟：那会退化成“读-改-写”竞态。
- 返回码 `1/2/3/4` 是内部契约：`1` 成功、`2` 已售罄、`3` 库存不足、`4` 重复购买。
- 脚本只能操作传入的 KEYS（集群模式下才能正确路由）。

MQ 投递失败时执行 `ROLLBACK_SCRIPT`：库存 `+1`、删锁、删售罄标记。注意它只能修 Redis，不涉及 DB。

## 3. 一人一单的三层防线

| 层 | 位置 | 作用 |
| --- | --- | --- |
| Redis 锁 | `seckill:order:{goodsId}:{userId}`，`SET NX EX 1800` | 挡住并发重复点击 |
| DB 唯一键 | `seckill_order(user_id, goods_id)` | 兜住锁过期/漏判 |
| 业务码 | `500202` | 前端可读的失败原因 |

锁过期 30 分钟意味着：同一用户 30 分钟后理论上能再次抢到，这正是“现状不修”的已知点。

## 4. RabbitMQ 手动 ACK

- `application.yml`：`acknowledge-mode: manual`、`prefetch: 1`、`concurrency: 10`。
- `mq/SeckillConsumer.java`：消费成功 `basicAck`；任何异常 `basicNack(requeue=true)`。
- 语义：消息只有在落库完成后才确认；失败会重新入队，因此**消费逻辑必须幂等**。当前靠唯一键“幂等”，但冲突时没有特判，会一直重试——这是风险而不是设计。
- 默认 Java 序列化，消息体 `SeckillMessage` 必须 `implements Serializable` 且 `serialVersionUID` 固定。

## 5. Sa-Token + 拦截器

- `StpUtil.login(id)` 把会话写进 Redis，`getTokenValue()` 返回 token 字符串。
- `interceptor/LoginInterceptor.java`：读 `Authorization` → 去掉 `Bearer ` 前缀 → `StpUtil.getLoginIdByToken` → 写入 ThreadLocal → `afterCompletion` 清理（防止线程池串号）。
- WebConfig 只拦 `/api/seckill/**` 与 `/api/order/**`，商品与登录接口公开。

## 6. 限流

`RateLimitInterceptor`：Redis `INCR ratelimit:seckill:{userId}`，首次 `EXPIRE 5`，超过 5 次抛 `500206`。固定窗口实现，简单但临界点会双倍放行——学习用途够用。

## 7. 防超卖的数据库兜底

`mapper/GoodsMapper.decrStock`：

```sql
UPDATE goods SET stock_count = stock_count - 1
WHERE id = ? AND stock_count > 0
```

用条件更新而不是先查后改：返回 0 行说明被别人抢完，天然避免超卖。

## 8. 前端轮询

`OrderResult.vue`：进入页面先 `POST /api/seckill/{goodsId}`，随后每 500ms `GET /api/order/result/{goodsId}`，`orderId > 0` 立即停止。响应拦截器已解包，业务失败会 reject，不用手动判断 `code`。

## 9. 建议阅读顺序

1. `schema.sql` → 看清表结构与唯一键
2. `common/CodeMsg.java` → 错误码全集
3. `SeckillServiceImpl` → Lua 与投递
4. `SeckillConsumer` → 落库与 ACK
5. `LoginInterceptor` / `RateLimitInterceptor`
6. `GoodsList.vue` / `OrderResult.vue`

## 10. 自测题

1. 为什么 Lua 里先判断售罄标记，再判断库存，最后才 `SET NX`？顺序换一下会怎样？
2. MQ 投递失败后回滚 Redis，如果进程在回滚前崩了，会留下什么不一致？
3. 消费端没有事务，扣库存成功但插订单失败，如何发现和修复？
4. 把限流从“5 秒 5 次”改成滑动窗口，需要改哪几处？
5. 商品列表要加 Redis 缓存，如何保证与 DB 库存一致？
