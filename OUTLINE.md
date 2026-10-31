# FlashSale 项目大纲

> 这是项目早期的大纲稿，保留原始设计口径。**其中多项描述与当前代码不符**（Redis 扣减方式、表数量、商品缓存、并行预热、RabbitMQ 镜像等），差异逐条列在[开发说明书第 14 节](docs/FlashSale-开发说明书.md)。动手前请以代码与说明书为准。

## 1. 目标

做一个“可被压测验证”的秒杀系统，重点不是功能多，而是：

- 瞬时高并发下不超卖、不重复下单
- Redis 挡住绝大部分无效请求，MySQL 只承接成功订单
- 链路可解释：每个失败都有明确业务码

## 2. 技术选型

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 后端 | Spring Boot 4.1.0 + Java 21 | WebMVC、Validation |
| 持久层 | MyBatis-Plus 3.5.12 | `BaseMapper` + 少量 `@Update` 自定义 SQL |
| 数据库 | MySQL 8 | 3 张业务表（`goods`、`order_info`、`seckill_order`）——**实际另有 `user` 表，共 4 张** |
| 缓存/闸门 | Redis | 库存、售罄标记、一人一单锁、结果、限流 |
| 消息 | RabbitMQ | 削峰异步落库，手动 ACK |
| 鉴权 | Sa-Token 1.44.0 | Header `Authorization`，30 分钟 |
| 压测 | JMeter 5.6.3 | 登录预热 + 秒杀 + 轮询 + 校验 SQL |
| 前端 | Vue 3 + Router 4 + Axios + Vite | 商品列表 / 登录 / 结果轮询 |

## 3. 模块划分

```
common     统一响应、错误码、异常、Redis Key 前缀
config     Redis / RabbitMQ / 线程池 / MVC 拦截器注册
controller 登录、商品、秒杀
service    业务编排（Impl 下三个实现）
mapper     MyBatis-Plus Mapper（含条件扣库存）
mq         生产者 / 消息体 / 消费者
interceptor 登录鉴权、限流
entity/dto 表映射与对外视图
```

## 4. 早期设计的秒杀流程（与现状有差异）

计划稿写的是“DECR + 失败 INCR 回滚 + SETNX 防重”：

1. 查 Redis 库存，`DECR`
2. 结果 < 0 则 `INCR` 回滚并返回库存不足
3. `SETNX` 一人一单锁
4. 成功后投递 MQ，消费端落库

**实际实现**：一段 Lua 脚本原子完成“售罄判断 → 库存判断 → `SET NX` 一人一单 → `DECR`”，只有 MQ 投递失败才执行回滚脚本。不与 Lua 拆分，也不要在 Lua 外用 `DECR` 补逻辑。

## 5. 其它早期设想（未落地）

| 设想 | 现状 |
| --- | --- |
| 商品列表 Redis 缓存 TTL 60s | 未实现，`GoodsServiceImpl` 直接查 DB |
| `CompletableFuture` 并行预热库存 | 未实现，`for` 顺序循环 |
| `rabbitmq:3-management` 镜像 | compose 用 `rabbitmq:management` |
| 线程池 / 自定义 `RedisTemplate` 参与业务 | 已定义但未使用，保留脚手架 |
| 订单状态流转（待支付/已支付/已取消） | 只写 `status=0`，无后续流转 |

## 6. 里程碑

1. 建库建表 + 种子数据（4 表、6 商品、2 用户）
2. 登录 → Token
3. 商品列表 / 详情
4. Lua 原子扣减 + MQ 投递
5. 消费端落库 + 手动 ACK
6. 结果轮询
7. 拦截器：登录 + 5 秒 5 次限流
8. 前端三页
9. JMeter 压测与数据校验

## 7. 验收口径

- 订单数 ≤ 初始库存；`seckill_order` 的 `(user_id, goods_id)` 无重复
- `Redis 剩余库存 + 成功订单数 = 初始库存`（同一轮运行内）
- 重复购买返回 `500202`，售罄返回 `500203`，超频返回 `500206`
