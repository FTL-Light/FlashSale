# FlashSale 使用与开发指南

> 面向“把项目跑起来 / 继续改”的操作手册。接口与行为细节以[开发说明书](docs/FlashSale-开发说明书.md)为准；本指南中的前端片段是项目早期原稿，与当前 `FlashSaleFrontend` 实现有差异（当前走 Vite 代理、`baseURL: '/api'`）。

## 1. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | `pom.xml` 指定；只有 17 时无法按文档启动 |
| Maven | 3.9.x | 用 `FlashSaleBackend/mvnw` 自动下载 |
| MySQL | 8 | 需手动建库或执行 `schema.sql` |
| Redis | 6+ | 本地默认 `localhost:6379`，无密码 |
| RabbitMQ | 3.x | `docker compose up -d rabbitmq`，management 插件 15672 |
| Node.js | 18+ | 前端 Vite |
| JMeter | 5.6.3 | 可选，压测用 |

## 2. 启动步骤

```powershell
# 1) RabbitMQ
docker compose up -d rabbitmq

# 2) 数据库（schema.sql 自带 CREATE DATABASE）
mysql -uroot -p < FlashSaleBackend/src/main/resources/schema.sql

# 3) 环境变量（密码无默认值，不设会启动失败）
$env:MYSQL_PASSWORD = "你的密码"

# 4) 后端
cd FlashSaleBackend
.\mvnw.cmd spring-boot:run

# 5) 前端（另开终端）
cd FlashSaleFrontend
npm install
npm run dev
```

启动成功标志：日志出现 Redis 库存预热、RabbitMQ 队列声明；访问 `http://localhost:5173/goods` 能看到 6 个商品。

种子账号：`13800000001 / 123456`、`13800000002 / 123456`。

## 3. 接口速查

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/user/login` | 否 | 返回 Sa-Token 字符串 |
| GET | `/api/goods` | 否 | 商品列表，含 `seckillStatus` |
| GET | `/api/goods/{goodsId}` | 否 | 商品详情，不存在返回 `500101` |
| POST | `/api/seckill/{goodsId}` | 是 | 成功返回排队提示；失败抛业务码 |
| GET | `/api/order/result/{goodsId}` | 是 | 未完成 `-1`，完成返回 `orderId` |

鉴权头：`Authorization: Bearer <token>`。

错误码：`500101` 参数校验、`500102` 未登录、`500201` 库存不足、`500202` 重复购买、`500203` 已售罄、`500204` 未开始、`500205` 已结束、`500206` 限流、`500301` 用户不存在、`500302` 密码错误。

## 4. 前端原稿：Axios 封装

```js
import axios from 'axios'

const instance = axios.create({ baseURL: 'http://localhost:8080' })

instance.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

instance.interceptors.response.use(res => {
  const body = res.data
  if (body.code !== 0) {
    alert(body.msg)
    return Promise.reject(new Error(body.msg))
  }
  return body.data
})

export default instance
```

要点：拦截器已经解了一层，页面里直接 `await listGoods()`，不要再取 `.data`。

当前仓库的实现改用相对路径 `baseURL: '/api'`，由 `vite.config.js` 代理到 `http://localhost:8080`，避免后端无 CORS 时跨域失败。

## 5. 前端路由

| 路由 | 组件 | 行为 |
| --- | --- | --- |
| `/` | 重定向 | 跳 `/goods` |
| `/login` | `LoginView.vue` | 登录成功后写 `localStorage.token` |
| `/goods` | `GoodsList.vue` | 商品卡片、倒计时、秒杀入口 |
| `/order/:goodsId` | `OrderResult.vue` | 发起秒杀并轮询结果 |

## 6. 调试技巧

- **秒杀一直返回 500201**：Redis 库存小于实际；重启应用会按 DB 重新预热并清除售罄标记。
- **登录 500302**：密码规则是 `MD5(salt + password)`，盐 `1a2b3c4d`；用 `prepare.sql` 生成的用户密码均为 `123456`。
- **秒杀成功但查不到订单**：看消费者日志；手动 ACK 模式下异常会 `basicNack(requeue=true)`。
- **重复消费 / 无限重试**：同一用户重复投递会撞 `seckill_order` 唯一键，消费者没有 `DuplicateKeyException` 特判，会持续重试（已知问题，见下）。
- **500102 频繁出现**：Sa-Token 默认 30 分钟过期，压测前重新登录。

## 7. 已知风险（原稿记录，现状未修）

| # | 风险 | 影响 |
| --- | --- | --- |
| 1 | 消费端三步 DB 操作无 `@Transactional` | 扣库存成功、订单写入失败会留下脏数据 |
| 2 | 唯一键冲突无特判 | `basicNack(requeue=true)` 导致无限重试 |
| 3 | 防重锁 TTL 30 分钟 | 30 分钟后同一用户可再次抢购 |
| 4 | 结果 Key TTL 30 分钟 | 超时后轮询只返回 `-1` |
| 5 | 启动预热会覆盖库存 | 重启会“复活”已售库存；实际代码会顺带删除售罄标记 |

修复需要明确授权；默认保持一比一现状。

## 8. 压测

见 [jmeter/压测操作指南.md](jmeter/压测操作指南.md)：先执行 `prepare.sql` 生成 500 用户，再按档位替换占位符运行。校验 SQL 与阈值在同一目录的 `JMETER_GUIDE.md` 中。
