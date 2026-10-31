# FlashSale · 高并发秒杀系统

一个学习演示型秒杀系统：Redis 扛瞬时扣减，RabbitMQ 削峰异步落库，Sa-Token 登录鉴权，JMeter 验证不超卖、不重复。

需求与实现依据见 [docs/FlashSale-开发说明书.html](docs/FlashSale-开发说明书.html)。

## 技术栈

- Java 21 / Spring Boot 4.1.0 / Maven
- MyBatis-Plus 3.5.12 + MySQL 8
- Redis（Lua 原子扣减）
- RabbitMQ（手动 ACK、异步落库）
- Sa-Token 1.44.0（Token 鉴权）
- 前端 Vue 3 + Vue Router 4 + Axios + Vite

## 目录

```
FlashSale/
├── docs/                       # 开发说明书
├── docker-compose.yml          # 仅 RabbitMQ
├── FlashSaleBackend/           # 后端
├── FlashSaleFrontend/          # 前端（按说明书第 11 节补建）
└── jmeter/                     # 压测脚本与数据准备
```

## 启动

1. 初始化数据库：

```powershell
mysql -uroot -p < FlashSaleBackend\src\main\resources\schema.sql
```

2. 设置环境变量（`MYSQL_PASSWORD` 无默认值，未设置启动失败）：

```powershell
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
```

| 变量 | 必填 | 默认值 | 用途 |
| --- | --- | --- | --- |
| `MYSQL_PASSWORD` | 是 | 无 | MySQL 密码，缺失时应用启动失败 |
| `MYSQL_USERNAME` | 否 | `root` | MySQL 用户名 |
| `RABBITMQ_USERNAME` | 否 | `guest` | RabbitMQ 用户名 |
| `RABBITMQ_PASSWORD` | 否 | `guest` | RabbitMQ 密码 |
| `JMETER_HOME` | 否 | `D:\jmeter\apache-jmeter-5.6.3` | JMeter 安装目录，仅压测脚本使用 |

3. 启动 Redis 与 RabbitMQ：

```powershell
redis-server
docker compose up -d          # 或本地启动 RabbitMQ
```

4. 启动后端：

```powershell
cd FlashSaleBackend
.\mvnw.cmd spring-boot:run
```

5. 启动前端：

```powershell
cd FlashSaleFrontend
npm install
npm run dev
```

前端通过 Vite 代理把 `/api` 转发到 `http://localhost:8080`，因为后端没有 CORS 配置。

## 快速验证

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/user/login `
  -ContentType "application/json" `
  -Body '{"phone":"13800000001","password":"123456"}'
$token = $login.data

Invoke-RestMethod http://localhost:8080/api/goods

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/seckill/1 `
  -Headers @{ Authorization = "Bearer $token" }

Invoke-RestMethod -Uri http://localhost:8080/api/order/result/1 `
  -Headers @{ Authorization = "Bearer $token" }
```

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/user/login` | 否 | 手机号 + 密码，返回 Token |
| GET | `/api/goods` | 否 | 商品列表（3 条种子数据） |
| GET | `/api/goods/{goodsId}` | 否 | 商品详情 |
| POST | `/api/seckill/{goodsId}` | 是 | 秒杀，成功返回“购买成功!正在排队...” |
| GET | `/api/order/result/{goodsId}` | 是 | 轮询结果，`-1` 排队中，`>0` 订单 ID |

统一响应：`{code, msg, data}`。业务失败 HTTP 仍是 200，需要读 `code`。

## 错误码

| code | 含义 |
| --- | --- |
| 0 | 成功 |
| 500 | 服务器异常 |
| 500101 | 参数校验失败（商品不存在） |
| 500102 | 未登录或 Token 无效 |
| 500201 | 库存不足 |
| 500202 | 请勿重复购买 |
| 500203 | 已售罄 |
| 500204 | 秒杀未开始 |
| 500205 | 秒杀已结束 |
| 500206 | 操作过于频繁 |
| 500301 | 用户不存在 |
| 500302 | 密码错误 |

## 核心机制

- 库存 Key：`seckill:stock:{goodsId}`；售罄标记：`seckill:soldout:{goodsId}`
- 一人一单锁：`seckill:order:{goodsId}:{userId}`，TTL 30 分钟
- 结果 Key：`seckill:result:{goodsId}:{userId}`，TTL 30 分钟
- 限流：`ratelimit:seckill:{userId}`，固定窗口 5 秒 5 次
- Lua 返回码：`1=成功 2=已售罄 3=库存不足 4=重复购买`
- MQ：DirectExchange `seckill.exchange` / Queue `seckill.queue` / RoutingKey `seckill.routingkey`
- 消费者手动 ACK，DB 条件扣库存（`stock_count > 0`）后写订单与 Redis 结果

## 压测

见 [jmeter/JMETER_GUIDE.md](jmeter/JMETER_GUIDE.md) 与 [jmeter/压测操作指南.md](jmeter/压测操作指南.md)。

```powershell
$env:JMETER_HOME = "D:\jmeter\apache-jmeter-5.6.3"
.\jmeter\run-test.ps1 -Mode medium
```

## 已知行为（复刻现状，未修复）

- 消费者没有 `@Transactional`，三步 DB 操作可能部分成功
- 唯一键冲突会 `basicNack(requeue=true)`，存在无限重试风险
- 防重锁与结果 Key 都是 30 分钟 TTL，过期后可重复购买 / 轮询返回 `-1`
- 启动时无条件用 DB 库存覆盖 Redis，并清除售罄标记
- 商品列表无 Redis 缓存，`stockCount` 来自 DB
- 没有 CORS、注册、支付、后台管理、死信队列

以上均为[开发说明书](docs/FlashSale-开发说明书.html)第 14 节列出的真实行为，默认保持原样。

## 相对说明书 v1.0 的修订

- 包名由 `com.kisssheep.flashsalebackend` 改为 `com.ftllight.flashsalebackend`，代码与说明书 v1.1 一致。
- 说明书 v1.0 的种子密码哈希 `b7797c6b0187825e79db224b88c12831` 与其声明的算法 `MD5(salt + password)` 对不上：
  `MD5("1a2b3c4d" + "123456") = 16add37301c95de2bc7abe1788a752e7`。为保证「密码 123456 能登录」，代码与说明书 v1.1
  均已采用实际计算结果，算法与密码规则未变。

## License

[MIT](LICENSE)
