# FlashSale · 高并发秒杀系统 · 开发说明书

> 仓库快照 2026-10-31 · 后端完整 / 前端已入库

这不是一份“产品愿景”。它是一份**可由 AI 直接执行的施工图**：把仓库里的代码、SQL、配置、Redis Key、MQ 拓扑、压测脚本拆成不可变契约。拿到本文档的 AI，应能在空目录中复刻出**可编译、可启动、接口行为一致、压测脚本可运行**的项目。

## 目录

- 00 使用说明与复刻定义
- 01 项目概览与范围
- 02 复刻交付清单
- 03 环境与启动
- 04 数据模型
- 05 接口契约
- 06 配置规格
- 07 核心机制
- 08 关键流程
- 09 模块实现规格
- 10 状态与边界
- 11 前端附录
- 12 压测规格
- 13 验收标准
- 14 差异与已知风险
- 15 AI 复刻执行手册

## 00 · 使用说明与“一比一”的定义

先明确复刻的边界。否则 AI 会“优化”掉关键细节，最后看起来像、跑起来不像。

### 0.1 事实等级

文档中的每条信息都按以下等级标记。复刻者不要把“文档描述”抬高到“代码事实”之上。

| 标记 | 含义 | 复刻处理 |
| --- | --- | --- |
| 代码事实 | 直接来自当前仓库源码、pom、yml、schema.sql、JMX。 | 必须逐字/逐行为复现。 |
| 推导事实 | 由代码执行路径、配置组合可确定的行为。 | 必须复现其可观察结果。 |
| 假设 | 仓库缺失或文档未覆盖，为形成完整方案而补充。 | 可调整；不得冒充代码事实。 |

### 0.2 “一比一复刻”的六个硬标准

1. **文件树一致。**包名 `com.ftllight.flashsalebackend`、类名、分层目录不重命名、不合并、不新增无关层。
2. **数据层一致。**4 张表、字段类型、唯一索引、种子数据、密码算法完全一致。
3. **接口契约一致。**5 个接口的方法、路径、入参、响应包裹、错误码、鉴权头一致。
4. **核心机制一致。**Redis Key 命名、Lua 脚本返回值语义、MQ 交换机/队列/路由键、手动 ACK 行为一致。
5. **启动行为一致。**应用启动即预热库存；登录返回 Sa-Token；秒杀先 Redis 后 MQ；轮询 `-1` / 订单 ID。
6. **验证方式一致。**JMeter 脚本结构、占位符替换、数据校验 SQL 可运行；本文第 13 节验收清单全部通过。

### 0.3 本文档不覆盖的部分

- P1 前端 `FlashSaleFrontend`：README 声称存在，但当前仓库快照中**没有该目录**。第 11 节给出基于 OUTLINE/GUIDE 的补建规格，并明确标注假设。
- 生产级高可用：集群、哨兵、死信队列、分布式锁、对账、监控告警均**未实现**，不要擅自补。
- 支付、发货、退款、商品管理后台、用户注册：均不在项目范围。

> **冲突处理规则**
>
> README 的“秒杀流程”写的是 `DECR` + 失败回滚 + `SETNX`，但实际代码是**一段 Lua 脚本原子完成售罄判断、库存判断、一人一单、扣减**。复刻时以 Lua 为准；不要把 README 的旧描述实现成代码。

### 0.4 推荐阅读顺序

#### 施工路径（要动手）

03 环境 → 04 数据 → 06 配置 → 09 模块 → 08 流程 → 13 验收。先让空项目跑起来，再逐层补齐。

#### 评审路径（要评审）

01 范围 → 05 接口 → 07 机制 → 10 状态边界 → 14 差异风险。重点看“行为是否可解释”。

## 01 · 项目概览与范围

一个“学习演示型”秒杀系统：用 Redis 扛住瞬时扣减，用 RabbitMQ 削峰异步落库，用 Sa-Token 做登录鉴权，用 JMeter 验证不超卖、不重复。

### 1.1 一句话定义

用户在限定时间窗口内，对限定库存的商品发起抢购；系统在 Redis 中原子地判定“是否有货、是否重复”，成功者进入 MQ 排队，消费者异步创建订单；前端轮询拿到订单 ID。

### 1.2 角色与场景

| 角色 | 能做什么 | 不能做什么 | 入口 |
| --- | --- | --- | --- |
| 未登录访客 | 查看商品列表、商品详情 | 秒杀、查订单结果 | `GET /api/goods` |
| 登录用户 | 秒杀、轮询自己的结果 | 查看他人订单、重复购买同一商品 | `POST /api/user/login` 后带 Token |
| MQ 消费者 | 扣 DB 库存、建订单、写 Redis 结果、ACK | 直接面对用户 | `seckill.queue` |
| 压测工具 | 批量登录、并发秒杀、轮询、校验数据 | 绕过业务规则 | JMeter / 脚本 |

### 1.3 核心用户旅程（P0）

**Entry** 打开商品列表 → **Auth** 手机号 + 密码登录 → **Action** 点击“立即秒杀” → **Queue** 返回“正在排队...” → **Poll** 每 500ms 查结果 → **Done** 拿到 orderId

完成条件：轮询接口返回的 `orderId > 0`，且数据库 `order_info` 中存在对应记录。恢复路径：重复购买 / 已售罄 / 未开始 / 已结束 / 限流 / Token 失效，均返回明确业务码，前端可提示并停留在商品页。

### 1.4 范围分层

| 优先级 | 能力 | 判定标准 |
| --- | --- | --- |
| P0 | 建库建表 + 种子数据 | 4 张表可创建，6 个商品 / 2 个用户可查 |
| P0 | 登录鉴权 | 正确密码返回 Token；错误密码/不存在用户返回业务码；受保护接口无 Token 返回 500102 |
| P0 | 商品列表 / 详情 | 返回秒杀价、库存、起止时间、`seckillStatus` |
| P0 | 秒杀资格判定与 Redis 扣减 | Lua 一次执行；成功才投递 MQ；失败不投递 |
| P0 | 异步落库 | DB 条件扣库存、写 `order_info`、写 `seckill_order`、写结果 Key、手动 ACK |
| P0 | 结果轮询 | 未完成返回 `-1`；完成返回订单 ID |
| P0 | 登录拦截 + 秒杀限流 | 未登录拒绝；同一用户 5 秒内超过 5 次返回 500206 |
| P1 | JMeter 压测脚本与数据准备 | 占位符可替换，能跑通登录→秒杀→轮询，并校验库存/防重 |
| P1 | 前端 Vue 3 | 仓库未入库；按第 11 节补建才完整 |

### 1.5 明确不做

支付回调、订单状态流转（1/2/3）、发货、退款、购物车、商品管理后台、用户注册、验证码、分布式部署、死信队列、缓存商品列表、CompletableFuture 并行预热。这些在文档里出现过，但**当前代码没有实现**，不要补。

### 1.6 可观察的成功标准

100 → 0

商品 1 初始库存；最多 100 个成功订单

-1 → orderId

轮询状态从不排队到成功

500202

同一用户第二次秒杀必被拒

压测后必须满足：`订单数 ≤ 初始库存`；`seckill_order` 无 `(user_id, goods_id)` 重复；`Redis 剩余库存 + 成功订单数 = 初始库存`（同一轮运行内）。

## 02 · 复刻交付清单

先照抄文件树，再填内容。文件树本身就是契约的一部分。

### 2.1 仓库文件树（源码与配置）

<!-- repository tree · tracked files -->
```text
FlashSale/
├── .gitattributes
├── .gitignore
├── CLAUDE.md                          # 代理/网络提示（非业务）
├── docker-compose.yml                 # 仅 RabbitMQ
├── README.md
├── OUTLINE.md
├── GUIDE.md
├── LEARNING.md
├── FlashSale-项目面试准备.md
├── FlashSale-面试自述话术.md
├── FlashSaleBackend/
│   ├── .gitattributes
│   ├── .gitignore
│   ├── mvnw / mvnw.cmd
│   ├── .mvn/wrapper/maven-wrapper.properties
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/ftllight/flashsalebackend/
│       │   │   ├── FlashSaleBackendApplication.java
│       │   │   ├── common/
│       │   │   │   ├── CodeMsg.java
│       │   │   │   ├── GlobalException.java
│       │   │   │   ├── GlobalExceptionHandler.java
│       │   │   │   ├── RedisTypeString.java
│       │   │   │   └── Result.java
│       │   │   ├── config/
│       │   │   │   ├── RabbitMQConfig.java
│       │   │   │   ├── RedisConfig.java
│       │   │   │   ├── ThreadPoolConfig.java
│       │   │   │   └── WebConfig.java
│       │   │   ├── controller/
│       │   │   │   ├── AuthController.java
│       │   │   │   ├── GoodsController.java
│       │   │   │   └── SeckillController.java
│       │   │   ├── dto/
│       │   │   │   ├── GoodsDTO.java
│       │   │   │   ├── LoginDto.java
│       │   │   │   └── SeckillResultDTO.java
│       │   │   ├── entity/
│       │   │   │   ├── Goods.java
│       │   │   │   ├── OrderInfo.java
│       │   │   │   ├── SeckillOrder.java
│       │   │   │   └── User.java
│       │   │   ├── interceptor/
│       │   │   │   ├── LoginInterceptor.java
│       │   │   │   └── RateLimitInterceptor.java
│       │   │   ├── mapper/
│       │   │   │   ├── GoodsMapper.java
│       │   │   │   ├── OrderInfoMapper.java
│       │   │   │   ├── SeckillOrderMapper.java
│       │   │   │   └── UserMapper.java
│       │   │   ├── mq/
│       │   │   │   ├── SeckillConsumer.java
│       │   │   │   ├── SeckillMessage.java
│       │   │   │   └── SeckillProducer.java
│       │   │   └── service/
│       │   │       ├── GoodsService.java
│       │   │       ├── SeckillService.java
│       │   │       ├── UserService.java
│       │   │       └── Impl/
│       │   │           ├── GoodsServiceImpl.java
│       │   │           ├── SeckillServiceImpl.java
│       │   │           └── UserServiceImpl.java
│       │   └── resources/
│       │       ├── application.yml
│       │       └── schema.sql
│       └── test/java/com/ftllight/flashsalebackend/
│           └── FlashSaleBackendApplicationTests.java
└── jmeter/
    ├── flashsale-load-test.jmx
    ├── users.csv
    ├── prepare.sql
    ├── run-test.bat
    ├── run-test.ps1
    ├── simulate.ps1
    ├── JMETER_GUIDE.md
    ├── 压测操作指南.md
    └── results/                       # 生成的 jtl + HTML 报告（可删除重跑）
```

### 2.2 关键文件职责

| 文件 | 级别 | 不可变职责 |
| --- | --- | --- |
| `FlashSaleBackendApplication` | P0 | `@SpringBootApplication` + `@MapperScan("com.ftllight.flashsalebackend.mapper")` |
| `common/Result.java` | P0 | 统一响应 `{code,msg,data}`，`success(data)` / `error(CodeMsg)` |
| `common/CodeMsg.java` | P0 | 所有错误码唯一来源，数值不可改 |
| `common/GlobalException*.java` | P0 | 业务异常携带 CodeMsg；`@ControllerAdvice` 统一转 JSON |
| `common/RedisTypeString.java` | P0 | 4 个 Redis Key 前缀 + `key(...)` 拼接 |
| `config/RabbitMQConfig.java` | P0 | DirectExchange / durable Queue / Binding 三件套 |
| `config/RedisConfig.java` | P0 | String Key + JSON Value 的 `RedisTemplate` |
| `config/WebConfig.java` | P0 | 注册登录拦截器与限流拦截器，路径规则不可改 |
| `config/ThreadPoolConfig.java` | P1 | 定义 `seckillExecutor`，当前业务未使用 |
| `controller/*` | P0 | 仅做参数接收、取当前用户、调用 Service、包 Result |
| `service/Impl/SeckillServiceImpl` | P0 | 预热 + 时间校验 + Lua 扣减 + MQ 投递 + 补偿 + 轮询 |
| `service/Impl/UserServiceImpl` | P0 | 手机号查用户、MD5(salt+password) 比对、Sa-Token 登录 |
| `service/Impl/GoodsServiceImpl` | P0 | 查全部/单个商品，转 DTO 并计算秒杀状态 |
| `mq/SeckillProducer` | P0 | `convertAndSend(exchange, routingKey, message)` |
| `mq/SeckillConsumer` | P0 | 手动 ACK；DB 扣减 → 订单 → 秒杀订单 → Redis 结果 |
| `interceptor/LoginInterceptor` | P0 | 解析 Bearer Token、Sa-Token 校验、ThreadLocal 用户 ID、请求后清理 |
| `interceptor/RateLimitInterceptor` | P0 | Redis INCR + EXPIRE，5 秒 5 次 |
| `resources/application.yml` | P0 | 端口、数据源、Redis 池、Rabbit 手动 ACK、Sa-Token、MyBatis-Plus |
| `resources/schema.sql` | P0 | 建库、4 张表、3 商品、2 用户 |
| `pom.xml` | P0 | Boot 4.1.0 / Java 21 / MP 3.5.12 / Sa-Token 1.44.0 / Redis / AMQP / Session |
| `jmeter/*` | P1 | 登录预热 + 秒杀压测 + 轮询 + 数据校验 |

### 2.3 缺失项与生成物

> **注意**
>
> **`FlashSaleFrontend/` 不在当前快照中。**README 与 OUTLINE 描述它是独立 Vue 3 项目，GUIDE 只给了 `src/api/request.js` 与路由表，没有页面实现。若用户要求“复刻完整产品”，按第 11 节补建；若要求“复刻当前仓库”，不要凭空创建前端。<br>`jmeter/results/` 是生成物，可删除后重跑；其中报告由 `simulate.ps1` 生成，属于模拟数据，不能当作真实压测结论。

### 2.4 禁止擅自改动

- 不改包名、类名、接口路径、错误码数值、Redis Key 前缀、MQ 名称。
- 不把 MyBatis-Plus 换成 JPA / 手写 XML；不把 `StringRedisTemplate` 换成 `RedisTemplate`。
- 不把 Lua 脚本拆成多条 Redis 命令；不把异步下单改回同步。
- 不新增注册、支付、后台管理、Swagger、CORS、统一日志框架。
- 不在未要求时“修复”第 14 节列出的已知问题——那会改变可观察行为，破坏一比一。

## 03 · 环境与启动

这个项目依赖 4 个外部组件。任何一项没起，秒杀链路都会以“看起来像业务错误”的方式失败。

### 3.1 版本要求

| 组件 | 版本 | 用途 / 备注 |
| --- | --- | --- |
| JDK | 21 | pom `<java.version>21</java.version>` |
| Spring Boot | 4.1.0 | 父 POM；使用 `spring-boot-starter-webmvc` 而非旧版 `-web` |
| Maven | Wrapper 自带 | Windows 用 `mvnw.cmd`，bash 用 `./mvnw` |
| MySQL | 8.x | 库名 `flashsale`，端口 3306 |
| Redis | 本地服务 | localhost:6379，无密码 |
| RabbitMQ | 4.x 或 Docker `rabbitmq:management` | 5672 / 管理台 15672，guest/guest |
| Node.js | 18+（仅前端） | 仓库未含前端；补建时需要 |
| JMeter | 5.6.3 | 脚本内硬编码 `D:\jmeter\apache-jmeter-5.6.3\bin` |

### 3.2 端口与环境变量

| 变量 / 端口 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 后端 HTTP |
| `MYSQL_PASSWORD` | **无默认，必填** | 未设置时应用启动失败 |
| `MYSQL_USERNAME` | `root` | 可覆盖 |
| `RABBITMQ_USERNAME` | `guest` | 可覆盖 |
| `RABBITMQ_PASSWORD` | `guest` | 可覆盖 |
| MySQL URL | `jdbc:mysql://localhost:3306/flashsale?...` | 关闭 SSL，时区 Asia/Shanghai |
| Redis | `localhost:6379` | Lettuce 池 |
| RabbitMQ | `localhost:5672` | 管理台 15672 |
| JMeter 结果 | `jmeter/results/` | JTL + HTML 报告 |

### 3.3 启动顺序

1. **初始化数据库。**执行 `schema.sql`；它会创建 `flashsale` 库、4 张表、6 个商品、2 个用户。
2. **设置密码环境变量。**PowerShell：`$env:MYSQL_PASSWORD = "你的密码"`；bash：`export MYSQL_PASSWORD="你的密码"`。IDE 运行时加到 Run Configuration 的环境变量。
3. **启动 Redis。**`redis-server`，确认 `redis-cli PING` 返回 `PONG`。
4. **启动 RabbitMQ。**本地安装直接跑 `rabbitmq-server.bat`；或在本仓库根目录执行 `docker compose up -d`。
5. **启动后端。**在 `FlashSaleBackend` 目录执行 `mvnw.cmd spring-boot:run`。看到 `Started FlashSaleBackendApplication` 即成功。
6. **（可选）启动前端。**仓库无前端；若已按第 11 节补建，在 `FlashSaleFrontend` 执行 `npm install` 后 `npm run dev`。

<!-- PowerShell · one-shot startup -->
```powershell
# 1) 初始化 DB（会提示输入密码）
mysql -uroot -p < FlashSaleBackend\src\main\resources\schema.sql

# 2) 环境变量
$env:MYSQL_PASSWORD = "你的 MySQL 密码"

# 3) 依赖服务
redis-server
docker compose up -d          # 或本地启动 RabbitMQ

# 4) 后端
cd FlashSaleBackend
.\mvnw.cmd spring-boot:run
```

### 3.4 启动后的快速验证

<!-- PowerShell · smoke test -->
```powershell
# 登录，记下返回的 data（Token）
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/user/login `
  -ContentType "application/json" `
  -Body '{"phone":"13800000001","password":"123456"}'
$token = $login.data

# 商品列表，应有 6 条
Invoke-RestMethod http://localhost:8080/api/goods

# 秒杀商品 1
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/seckill/1 `
  -Headers @{ Authorization = "Bearer $token" }

# 轮询结果：-1 排队中，>0 即订单 ID
Invoke-RestMethod -Uri http://localhost:8080/api/order/result/1 `
  -Headers @{ Authorization = "Bearer $token" }
```

### 3.5 启动失败排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动即失败，提示占位符未解析 | `MYSQL_PASSWORD` 未设置 | 设置环境变量后重启；不要写进 yml |
| `Communications link failure` | MySQL 未启动 / 库不存在 / 密码错 | 先跑 `schema.sql`，再检查端口 |
| `Unable to connect to Redis` | Redis 未启动 | `redis-cli PING` |
| `Connection refused: 5672` | RabbitMQ 未启动 | `docker compose up -d` 或本地启动 |
| 秒杀返回 500，日志有 MQ 发送失败 | 交换机/队列未声明或 Broker 掉线 | 确认应用已启动并声明 `seckill.exchange` / `seckill.queue` |
| 建表报 `user` 语法错误 | `user` 是保留字 | DDL 中必须用反引号 `\`user\`` |
| 登录成功但秒杀 500102 | Token 未带 `Bearer` 前缀 | 请求头必须是 `Authorization: Bearer <token>` |

### 3.6 重置一次完整运行

<!-- PowerShell · reset -->
```powershell
# 清 Redis 秒杀相关 Key（保留 Sa-Token 不影响；也可 FLUSHDB）
redis-cli --scan --pattern "seckill:*" | ForEach-Object { redis-cli DEL $_ }
redis-cli --scan --pattern "ratelimit:seckill:*" | ForEach-Object { redis-cli DEL $_ }

# 重置数据：重新执行 schema.sql（先 DROP 或手动清表）
mysql -uroot -p -e "DROP DATABASE IF EXISTS flashsale;"
mysql -uroot -p < FlashSaleBackend\src\main\resources\schema.sql

# 重启后端，触发 @PostConstruct 库存预热
```

## 04 · 数据模型

4 张表：用户、商品、订单、秒杀防重。库存同时存在 DB 与 Redis，Redis 是“闸门”，DB 是“最终账本”。

### 4.1 关系

**user** 1 个用户 → **order_info** N 个订单 → **goods** 1 个商品 → **seckill_order** (user_id, goods_id) 唯一

没有外键约束；关联靠业务字段。防重最终由 `seckill_order` 的唯一索引兜底。

### 4.2 完整 DDL（逐字复刻）

<!-- schema.sql -->
```sql
CREATE DATABASE IF NOT EXISTS flashsale DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE flashsale;

CREATE TABLE IF NOT EXISTS `user` (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone       VARCHAR(20)  NOT NULL UNIQUE COMMENT '手机号',
    password    VARCHAR(100) NOT NULL COMMENT '密码(MD5)',
    salt        VARCHAR(20)  NOT NULL COMMENT '盐值',
    nickname    VARCHAR(50)  DEFAULT '' COMMENT '昵称',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS goods (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL COMMENT '商品名称',
    title           VARCHAR(500)  DEFAULT '' COMMENT '商品描述',
    image           VARCHAR(500)  DEFAULT '' COMMENT '商品图片',
    price           DECIMAL(10,2) NOT NULL COMMENT '原价',
    seckill_price   DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    stock_count     INT           NOT NULL COMMENT '秒杀库存',
    start_time      DATETIME      NOT NULL COMMENT '秒杀开始时间',
    end_time        DATETIME      NOT NULL COMMENT '秒杀结束时间',
    version         INT           DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

CREATE TABLE IF NOT EXISTS order_info (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT         NOT NULL,
    goods_id    BIGINT         NOT NULL,
    goods_name  VARCHAR(100)   NOT NULL,
    goods_count INT            DEFAULT 1,
    goods_price DECIMAL(10,2)  NOT NULL COMMENT '成交价',
    status      TINYINT        DEFAULT 0 COMMENT '0=新建 1=已支付 2=已发货 3=已完成',
    create_time DATETIME       DEFAULT CURRENT_TIMESTAMP,
    pay_time    DATETIME       DEFAULT NULL,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS seckill_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    goods_id    BIGINT NOT NULL,
    order_id    BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_goods (user_id, goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';
```

### 4.3 字段约束与复刻要点

| 表 | 字段 | 类型 | 约束 / 说明 |
| --- | --- | --- | --- |
| `user` | `phone` | VARCHAR(20) | `NOT NULL UNIQUE`；登录唯一标识 |
| `user` | `password` | VARCHAR(100) | MD5 小写十六进制；不是明文 |
| `user` | `salt` | VARCHAR(20) | 与密码拼接后做 MD5 |
| `goods` | `price` / `seckill_price` | DECIMAL(10,2) | 金额必须用 BigDecimal；种子秒杀价 0.01 |
| `goods` | `stock_count` | INT | DB 最终库存；消费者条件扣减 |
| `goods` | `start_time` / `end_time` | DATETIME | 秒杀窗口；服务端用 `LocalDateTime.now()` 比较 |
| `goods` | `version` | INT DEFAULT 0 | 声明了乐观锁字段，但当前 SQL 未使用 |
| `order_info` | `goods_price` | DECIMAL(10,2) | 成交价 = 下单时商品的 `seckill_price` |
| `order_info` | `status` | TINYINT DEFAULT 0 | 0 新建；1/2/3 未实现流转 |
| `seckill_order` | `uk_user_goods` | UNIQUE(user_id, goods_id) | 防重的最后一道防线；重复插入抛 DuplicateKeyException |

### 4.4 种子数据（必须一致）

<!-- schema.sql · seed -->
```sql
INSERT INTO goods (name, title, image, price, seckill_price, stock_count, start_time, end_time)
VALUES
    ('iPhone 16 Pro', '最新款旗舰手机', '/images/iphone16.jpg', 8999.00, 0.01, 100, '2025-01-01 00:00:00', '2027-12-31 23:59:59'),
    ('AirPods Pro 3', '主动降噪耳机', '/images/airpods.jpg', 1999.00, 0.01, 200, '2025-01-01 00:00:00', '2027-12-31 23:59:59'),
    ('iPad Pro M4', 'M4芯片平板', '/images/ipad.jpg', 6799.00, 0.01, 50,  '2025-01-01 00:00:00', '2027-12-31 23:59:59'),
    ('MacBook Air M3', '轻薄本 13 英寸', '/images/macbook-air.jpg', 8999.00, 0.01, 80,  '2025-01-01 00:00:00', '2027-12-31 23:59:59'),
    ('Apple Watch Ultra 2', '户外运动手表', '/images/watch-ultra.jpg', 6499.00, 0.01, 120, '2025-01-01 00:00:00', '2027-12-31 23:59:59'),
    ('HomePod mini', '智能音箱', '/images/homepod-mini.jpg', 749.00, 0.01, 300, '2025-01-01 00:00:00', '2027-12-31 23:59:59');

INSERT INTO user (phone, password, salt, nickname)
VALUES
    ('13800000001', '16add37301c95de2bc7abe1788a752e7', '1a2b3c4d', '测试用户1'),
    ('13800000002', '16add37301c95de2bc7abe1788a752e7', '1a2b3c4d', '测试用户2');
```

> **密码算法（代码事实）**
>
> 数据库存的是 `MD5(salt + rawPassword)` 的小写十六进制。测试密码 `123456`，salt `1a2b3c4d`，结果为 `16add37301c95de2bc7abe1788a752e7`。复刻时不要换成 BCrypt，否则登录行为不一致。

### 4.5 状态枚举

| 字段 | 值 | 含义 | 产生位置 |
| --- | --- | --- | --- |
| `GoodsDTO.seckillStatus` | 0 | 未开始（`now < startTime`） | `GoodsDTO.from()` 实时计算，不落库 |
| `GoodsDTO.seckillStatus` | 1 | 进行中（`startTime ≤ now ≤ endTime`） |  |
| `GoodsDTO.seckillStatus` | 2 | 已结束（`now > endTime`） |  |
| `order_info.status` | 0 | 新建 | 消费者固定写入 0 |
| `order_info.status` | 1/2/3 | 已支付 / 已发货 / 已完成 | 未实现，仅注释声明 |
| `SeckillResultDTO.orderId` | -1 | 还在排队 / 无结果 | `queryResult()` |
| `SeckillResultDTO.orderId` | >0 | 下单成功，返回订单主键 | `queryResult()` 读取 Redis |

### 4.6 数据不变量

- 同一 `phone` 只能有一个用户；同一 `(user_id, goods_id)` 只能有一条 `seckill_order`。
- 逻辑上 `goods.stock_count ≥ 0`；消费者通过 `WHERE stock_count > 0` 保证不扣成负数。
- 成功订单数不应超过初始库存；Redis 剩余库存 + 成功订单数 = 初始库存（同一轮、无外部干预）。
- `order_info.id` 是轮询返回的订单 ID；`seckill_order.order_id` 关联它。
- 时间比较用服务器本地时区（Asia/Shanghai 由 JDBC URL 指定；JVM 时区需一致）。

## 05 · 接口契约

5 个接口，统一响应包裹。业务失败通常仍是 HTTP 200，调用方必须读 `code`，不能只看状态码。

### 5.1 统一响应

<!-- Result<T> · JSON shape -->
```json
{
  "code": 0,
  "msg": "success",
  "data": { ... }
}
```

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `code` | int | `0` 成功；非 0 为业务错误码 |
| `msg` | string | 成功固定 `success`；失败为 CodeMsg 文案 |
| `data` | T / null | 失败时为 `null` |

> **HTTP 语义**
>
> `GlobalExceptionHandler` 直接返回 `Result`，未设置 `@ResponseStatus`，因此业务异常（未登录、库存不足、限流等）**HTTP 状态码仍是 200**。前端 Axios 拦截器据此判断 `body.code !== 0`。

### 5.2 错误码总表（数值不可改）

| code | 枚举 | msg | 触发场景 |
| --- | --- | --- | --- |
| `0` | SUCCESS | success | 一切成功响应 |
| `500` | SERVER_ERROR | 服务器异常 | 未捕获异常；Lua 返回 null；MQ 投递失败补偿后 |
| `500101` | PARAM_ERROR | 参数校验失败 | 商品不存在（列表详情/秒杀） |
| `500102` | UNAUTHORIZED | 未登录或Token无效 | 无 `Bearer` 头；Token 无效/过期 |
| `500201` | STOCK_EMPTY | 库存不足 | Lua 返回 3（库存 ≤ 0 或不存在） |
| `500202` | SECKILL_REPEATED | 请勿重复购买 | Lua 返回 4（防重锁已存在） |
| `500203` | SECKILL_SOLD_OUT | 已售罄 | Lua 返回 2（售罄标记存在） |
| `500204` | SECKILL_NOT_STARTED | 秒杀未开始 | `startTime.isAfter(now)` |
| `500205` | SECKILL_ENDED | 秒杀已结束 | `endTime.isBefore(now)` |
| `500206` | RATE_LIMIT | 操作过于频繁，请稍后再试 | 同一用户 5 秒内第 6 次秒杀 |
| `500301` | USER_NOT_FOUND | 用户不存在 | 手机号查不到 |
| `500302` | PASSWORD_ERROR | 密码错误 | MD5 比对不一致 |

### 5.3 鉴权规则

- 登录接口无需 Token；商品列表/详情无需 Token；秒杀与轮询必须 Token。
- 请求头格式：`Authorization: Bearer <token>`。注意 `Bearer` 后有一个空格。
- 拦截器读取 `substring(7)` 得到 token，再调 `StpUtil.getLoginIdByToken(token)`；loginId 是用户主键。
- Sa-Token 配置 `token-name: Authorization`、`is-read-header: true`、`timeout: 1800` 秒。
- 用户 ID 放入 `ThreadLocal`，请求结束必须 `remove()`。

### 5.4 接口逐个说明

#### POST `/api/user/login`

认证：否 · Content-Type: application/json

- **请求体**：`{"phone":"13800000001","password":"123456"}`；两个字段均 `@NotBlank`
- **成功响应**：`{"code":0,"msg":"success","data":"<sa-token>"}`
- **失败**：用户不存在 `500301`；密码错误 `500302`
- **副作用**：Sa-Token 在 Redis 中创建会话；返回 token 字符串

<!-- response example -->
```json
{
  "code": 0,
  "msg": "success",
  "data": "a1b2c3d4e5f6..."
}
```

注意：`@Valid` 校验失败抛出的是 Spring 的校验异常，而 `GlobalExceptionHandler` 只显式处理 `GlobalException`，其余走兜底 `Exception` 分支，因此**空手机号/空密码实际返回 `500 服务器异常`**，而不是 `500101`。复刻现状时保留该行为。

#### GET `/api/goods`

认证：否

- **响应**：`Result<List<GoodsDTO>>`，按数据库查询顺序返回（无排序）
- **字段**：`id,name,title,image,price,seckillPrice,stockCount,startTime,endTime,seckillStatus`
- **失败**：数据库异常 → `500`

<!-- response example · one item -->
```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "name": "iPhone 16 Pro",
      "title": "最新款旗舰手机",
      "image": "/images/iphone16.jpg",
      "price": 8999.00,
      "seckillPrice": 0.01,
      "stockCount": 100,
      "startTime": "2025-01-01T00:00:00",
      "endTime": "2027-12-31T23:59:59",
      "seckillStatus": 1
    }
  ]
}
```

#### GET `/api/goods/{goodsId}`

认证：否

- **响应**：`Result<GoodsDTO>`，字段同上
- **失败**：商品不存在 → `500101 参数校验失败`

README 的 API 表没有列出这个接口，但代码存在且可访问。复刻时必须保留。

#### POST `/api/seckill/{goodsId}`

认证：是 · Authorization: Bearer &lt;token>

- **请求体**：无
- **成功响应**：`{"code":0,"msg":"success","data":"购买成功!正在排队..."}`
- **失败**：`500101` 商品不存在；`500204` 未开始；`500205` 已结束；`500203` 已售罄；`500201` 库存不足；`500202` 重复购买；`500206` 限流；`500102` 未登录；`500` MQ 失败
- **前置**：已登录；商品存在；时间在窗口内；Redis 已预热
- **副作用**：库存 Key 原子 -1；防重锁写入；向 MQ 投递一条消息

<!-- request / response -->
```json
POST /api/seckill/1
Authorization: Bearer a1b2c3d4e5f6...

{"code":0,"msg":"success","data":"购买成功!正在排队..."}
```

> **顺序很重要**
>
> 先校验商品存在 → 再校验时间 → 再执行 Lua（售罄 → 库存 → 一人一单 → 扣减）→ 再投递 MQ。不要调换，否则错误码语义会变。

#### GET `/api/order/result/{goodsId}`

认证：是 · Authorization: Bearer &lt;token>

- **响应**：`Result<SeckillResultDTO>`，其中 `orderId` 为 `-1` 或订单主键
- **成功示例**：`{"code":0,"msg":"success","data":{"orderId":123}}`
- **排队示例**：`{"code":0,"msg":"success","data":{"orderId":-1}}`
- **失败**：`500102` 未登录
- **语义**：只读 Redis 结果 Key；不查数据库

消费者写入结果 Key 后，才可能返回 >0；写入前一直是 `-1`。结果 Key 有 30 分钟 TTL，超时后再查仍返回 `-1`（订单其实已存在）。

### 5.5 字段字典（GoodsDTO / 结果）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 商品主键 |
| `name` / `title` / `image` | String | 展示字段；图片路径是相对路径 |
| `price` / `seckillPrice` | BigDecimal | 原价 / 秒杀价 |
| `stockCount` | Integer | 来自 DB 的库存，不是 Redis 实时库存 |
| `startTime` / `endTime` | LocalDateTime | ISO 格式（`2025-01-01T00:00:00`） |
| `seckillStatus` | Integer | 0 未开始 / 1 进行中 / 2 已结束，实时计算 |
| `orderId` | Long | -1 或订单主键 |

> **没有 CORS 配置**
>
> 后端**没有**`@CrossOrigin` 或 `CorsConfiguration`。若前端独立运行在 5173，必须用 Vite 代理，或由复刻者明确被授权后才加 CORS。不要在“一比一”模式里擅自加。

## 06 · 配置规格

配置决定行为。尤其是 RabbitMQ 的 `acknowledge-mode: manual`、Sa-Token 的 `token-name`、MyBatis-Plus 的驼峰映射，改一个就会“看起来能跑、行为不对”。

### 6.1 pom.xml 关键依赖

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `spring-boot-starter-parent` | 4.1.0 | 父 POM |
| `spring-boot-starter-validation` | 继承 | @Valid / @NotBlank |
| `mybatis-plus-spring-boot3-starter` | 3.5.12 | ORM、BaseMapper、条件构造器 |
| `spring-boot-starter-webmvc` | 继承 | Spring MVC（Boot 4 的 starter 名） |
| `spring-boot-devtools` | 继承 | 开发热重载（runtime/optional） |
| `mysql-connector-j` | 继承 | MySQL 驱动（runtime） |
| `lombok` | 继承 | @Data / @RequiredArgsConstructor / @Slf4j |
| `spring-boot-starter-data-redis` | 继承 | RedisTemplate / StringRedisTemplate / Lettuce |
| `spring-boot-starter-amqp` | 继承 | RabbitTemplate / @RabbitListener |
| `spring-session-data-redis` | 继承 | 已引入，但当前业务未使用 Spring Session |
| `sa-token-spring-boot3-starter` | 1.44.0 | 登录、Token、Redis 会话 |

测试依赖：`mybatis-plus-spring-boot3-starter-test`、`spring-boot-starter-validation-test`、`spring-boot-starter-webmvc-test`（均 3.5.12 / 继承）。构建插件必须配置 Lombok 的 `annotationProcessorPaths`，否则 Java 21 下注解处理可能失效。

### 6.2 application.yml（逐字复刻）

<!-- src/main/resources/application.yml -->
```yaml
server:
  port: 8080

spring:
  application:
    name: FlashSaleBackend

  datasource:
    url: jdbc:mysql://localhost:3306/flashsale?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver

  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 1000
          max-idle: 200
          min-idle: 50
          max-wait: 3000ms

  rabbitmq:
    host: localhost
    port: 5672
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        concurrency: 10
        max-concurrency: 20
        prefetch: 1
        acknowledge-mode: manual
    template:
      retry:
        enabled: true
        initial-interval: 1000ms

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: com.ftllight.flashsalebackend.entity
  global-config:
    db-config:
      id-type: auto
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

sa-token:
  token-name: Authorization
  timeout: 1800
  is-read-header: true
  is-log: true
```

### 6.3 docker-compose.yml（逐字复刻）

<!-- docker-compose.yml -->
```yaml
services:
  rabbitmq:
    image: rabbitmq:management
    container_name: flashsale-rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    environment:
      - RABBITMQ_DEFAULT_USER=guest
      - RABBITMQ_DEFAULT_PASS=guest

volumes:
  rabbitmq_data:
```

只容器化 RabbitMQ；MySQL 与 Redis 使用本地服务。镜像标签是 `rabbitmq:management`，不是 OUTLINE 里写的 `3-management`。

### 6.4 配置不变量

| 配置 | 值 | 为什么不能改 |
| --- | --- | --- |
| `sa-token.token-name` | Authorization | 拦截器与前端都按这个头读 Token |
| `sa-token.timeout` | 1800 | Token 30 分钟失效；与防重锁 TTL 呼应 |
| `rabbitmq.listener.simple.acknowledge-mode` | manual | 消费者用 Channel 手动 ACK/NACK；改成 auto 会改变消息可靠性 |
| `rabbitmq.listener.simple.prefetch` | 1 | 每个消费者一次取一条，配合手动 ACK |
| `concurrency / max-concurrency` | 10 / 20 | 消费者并发度；影响落库吞吐 |
| `mybatis-plus.configuration.map-underscore-to-camel-case` | true | 实体不用写 @TableField；关掉后字段映射全错 |
| `mybatis-plus.global-config.db-config.id-type` | auto | 依赖数据库自增主键 |
| `spring.datasource.password` | `${MYSQL_PASSWORD}` | 无默认值；未设置直接启动失败 |

### 6.5 配置类行为

#### RedisConfig（代码事实）

定义 `RedisTemplate<String,Object>`：Key 用 `StringRedisSerializer`，Value/HashValue 用 `GenericJacksonJsonRedisSerializer`，内部 `tools.jackson.databind.ObjectMapper`（Boot 4 / Jackson 3）。当前业务实际使用 `StringRedisTemplate`，该 Bean 未被注入使用。

#### ThreadPoolConfig（代码事实）

`@EnableAsync`；Bean 名 `seckillExecutor`：core 5、max 10、queue 100、keepAlive 60s、前缀 `seckillExecutor-`、拒绝策略 `CallerRunsPolicy`。当前没有任何 `@Async` 调用它。

#### WebConfig（代码事实）

登录拦截器拦 `/api/seckill/**`、`/api/order/**`；限流拦截器只拦 `/api/seckill/**`。排除列表写了 login/goods，但本来就不在拦截范围。

#### RabbitMQConfig（代码事实）

DirectExchange `seckill.exchange`；durable Queue `seckill.queue`；routing key `seckill.routingkey`；三者由 Spring 启动时声明。

## 07 · 核心机制

秒杀系统的“灵魂”只有三件事：Redis 原子闸门、MQ 削峰、DB 最终一致性兜底。其余都是围绕它们的脚手架。

### 7.1 运行时拓扑

**Client** 浏览器 / JMeter → **Filter** LoginInterceptor → **Filter** RateLimitInterceptor → **Controller** SeckillController → **Redis** Lua 原子扣减 → **MQ** seckill.exchange → **Consumer** 异步落库 → **MySQL** 4 张表

**Client** 每 500ms 轮询 → **API** /api/order/result/{goodsId} → **Redis** seckill:result:{goodsId}:{userId} → **Return** -1 或 orderId

### 7.2 Redis Key 设计（前缀不可改）

| Key | 类型 | TTL | 写入 / 读取 |
| --- | --- | --- | --- |
| `seckill:stock:{goodsId}` | String | 无 | 启动预热写；Lua GET/DECR；压测校验读 |
| `seckill:soldout:{goodsId}` | String "1" | 无 | Lua 在库存 ≤0 或扣到 0 时写；Lua 先查 |
| `seckill:order:{goodsId}:{userId}` | String | 1800s | Lua SET NX 写 "1"；消费者覆盖为 orderId |
| `seckill:result:{goodsId}:{userId}` | String | 1800s | 消费者写 orderId；轮询接口读 |
| `ratelimit:seckill:{userId}` | String 计数 | 5s | 限流拦截器 INCR + EXPIRE |
| `satoken:*` | Sa-Token 管理 | 1800s | 登录时创建；拦截器间接读取 |

> **Key 顺序陷阱**
>
> `seckill:order:{goodsId}:{userId}` 与 `seckill:result:{goodsId}:{userId}` 都是**商品在前、用户在后**。README 写的 `seckill:user:{userId}:{goodsId}` 是旧描述，不要照抄。

### 7.3 Lua 脚本（必须原样）

秒杀主脚本一次执行完成：售罄判断 → 库存判断 → 一人一单 → 扣减。返回值语义固定。

<!-- SeckillServiceImpl · SECKILL_SCRIPT -->
```text
-- KEYS[1]=库存  KEYS[2]=售罄标记  KEYS[3]=用户防重锁
-- ARGV[1]=锁过期秒数
-- 返回 1=成功 2=已售罄 3=库存不足 4=重复购买
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 2
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
    redis.call('SET', KEYS[2], '1')
    return 3
end

local locked = redis.call('SET', KEYS[3], '1', 'NX', 'EX', tonumber(ARGV[1]))
if not locked then
    return 4
end

local left = redis.call('DECR', KEYS[1])
if left == 0 then
    redis.call('SET', KEYS[2], '1')
end
return 1
```

<!-- SeckillServiceImpl · ROLLBACK_SCRIPT -->
```text
-- 投递 MQ 失败时的补偿：回滚库存、释放防重锁、清除可能被置上的售罄标记
-- KEYS[1]=库存  KEYS[2]=用户防重锁  KEYS[3]=售罄标记
redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])
redis.call('DEL', KEYS[3])
return 1
```

为什么用 Lua：避免“DECR 成功 → 后续失败 → 回滚”之间被其它请求插入，导致短暂超卖或库存不一致。Lua 在 Redis 单线程内原子执行。

### 7.4 RabbitMQ 拓扑

| 元素 | 名称 | 说明 |
| --- | --- | --- |
| Exchange | `seckill.exchange` | DirectExchange |
| Queue | `seckill.queue` | `QueueBuilder.durable(...)` |
| Routing Key | `seckill.routingkey` | 绑定关系 |
| Message | `SeckillMessage{userId, goodsId}` | 实现 `Serializable`，`serialVersionUID=1L` |
| Producer | `SeckillProducer.send()` | `rabbitTemplate.convertAndSend(...)` |
| Consumer | `@RabbitListener(queues="seckill.queue", ackMode="MANUAL")` | 方法参数含 `Channel` 与 `@Header(AmqpHeaders.DELIVERY_TAG)` |

> **序列化说明**
>
> `SeckillMessage` 实现 `Serializable`；项目没有自定义 `Jackson2JsonMessageConverter`，因此走 Spring AMQP 默认消息转换（Java 序列化）。复刻时不要额外加 JSON Converter，否则消息体格式与现有队列不兼容。

### 7.5 登录与限流

#### 登录拦截器（代码事实）

读 `Authorization` → 必须以 `Bearer` 开头 → `substring(7)` → `StpUtil.getLoginIdByToken(token)` → 转 `Long` → 存 ThreadLocal。`afterCompletion` 必须 `remove()`。无效 Token 抛 `500102`。

#### 限流拦截器（代码事实）

Key `ratelimit:seckill:{userId}`；`INCR`；首次（count==1）设 5 秒过期；`count > 5` 抛 `500206`。未取到 userId 时直接放行（但登录拦截器先执行，正常请求必有 userId）。

限流是“固定窗口”：从第一次请求起 5 秒内最多 5 次，窗口结束后 Key 自动消失。不是滑动窗口。

### 7.6 启动预热

`SeckillServiceImpl.initStock()` 标注 `@PostConstruct`，Bean 初始化后执行：查全部商品 → 对每个商品写 `seckill:stock:{id}` = DB `stock_count` → 删除 `seckill:soldout:{id}`。

- 每次都**无条件覆盖** Redis 库存；运行中手工改 Redis 会被下次启动冲掉。
- 会清理残留售罄标记，避免“DB 有库存但 Redis 一直售罄”。
- 不清理防重锁与结果 Key。
- 当前是 `for` 顺序循环，不是 OUTLINE 所说的 `CompletableFuture` 并行。

### 7.7 机制不变量

- 秒杀接口的成功返回**不代表订单已落库**，只代表已进入 MQ。
- 库存扣减的“第一道闸门”是 Redis；DB 扣减是第二道防线，且带 `stock_count > 0`。
- 一人一单的第一道闸门是 Redis `SET NX`；最终兜底是 `seckill_order` 唯一索引。
- 消费者必须手动 ACK；异常时 NACK 且 `requeue=true`。
- 轮询只读 Redis，不查 DB；因此结果 Key 的 TTL 决定“可查询窗口”。

## 08 · 关键流程

按“触发 → 校验 → 处理 → 结果 → 状态变化”来复刻。每个流程都给出前置条件与失败分支，避免 AI 只写 happy path。

### 8.1 登录流程

**Trigger** POST /api/user/login → **Validate** @Valid 非空 → **Query** phone 查 user → **Hash** MD5(salt+password) → **Compare** 与 DB 比对 → **Login** StpUtil.login(id) → **Return** token 字符串

| 步骤 | 动作 | 规则 / 失败 |
| --- | --- | --- |
| 1 | 接收 `LoginDto{phone,password}` | 字段均 `@NotBlank`；校验失败走兜底异常 → `500` |
| 2 | `LambdaQueryWrapper.eq(User::getPhone, phone)` + `selectOne` | 查不到 → `GlobalException(USER_NOT_FOUND)` → `500301` |
| 3 | `MD5(salt + rawPassword)`，小写 hex | 与 DB `password` 不等 → `500302` |
| 4 | `StpUtil.login(user.getId())`；`StpUtil.getTokenValue()` | Sa-Token 写入 Redis；返回 token |
| 5 | Controller 包 `Result.success(token)` | HTTP 200，`code=0` |

登录不返回用户昵称/ID，只返回 token。前端若需要用户信息，需另加接口——但当前项目没有。

### 8.2 商品列表 / 详情流程

- 列表：`goodsMapper.selectList(null)` → 逐条 `GoodsDTO.from()` → 返回 List。无排序、无分页、无 Redis 缓存。
- 详情：`selectById(goodsId)` → 为 null 抛 `PARAM_ERROR` → 否则转 DTO。
- `seckillStatus` 在 DTO 转换时按服务器当前时间实时计算，不落库。
- `stockCount` 返回的是 DB 库存，不是 Redis 实时剩余；秒杀后列表页库存不会立即减少（除非重新查 DB，消费者落库后才变）。

### 8.3 秒杀请求决策流程

**01** 取 userId → **02** 商品存在？ → **03** 时间窗口？ → **04** Lua 原子判定 → **05** 投递 MQ → **06** 返回排队中

| 步骤 | 判断 / 动作 | 结果 |
| --- | --- | --- |
| 1 | 登录拦截器解析 Token，取 `userId` | 无 Token / 无效 → `500102` |
| 2 | 限流：`ratelimit:seckill:{userId}` INCR | >5 → `500206` |
| 3 | `goodsMapper.selectById(goodsId)` | null → `500101` |
| 4 | `startTime.isAfter(now)` | true → `500204` |
| 5 | `endTime.isBefore(now)` | true → `500205` |
| 6 | 执行 `SECKILL_SCRIPT`，KEYS = 库存 / 售罄 / 防重锁 | 返回 2 → `500203`；3 → `500201`；4 → `500202`；null → `500` |
| 7 | 返回 1：构造 `SeckillMessage(userId, goodsId)`，`producer.send()` | 异常 → 回滚脚本 + `500` |
| 8 | 成功 | `Result.success("购买成功!正在排队...")` |

> **Lua 返回码是内部契约**
>
> `1=成功，2=已售罄，3=库存不足，4=重复购买`。不要把 2/3/4 合并成一个错误，否则前端无法区分“卖完了”和“你买过了”。

### 8.4 消费者落库流程

**MQ** seckill.queue → **1** 查商品 → **2** DB 条件扣库存 → **3** insert order_info → **4** insert seckill_order → **5** 写 Redis 结果 → **6** basicAck

| 步骤 | 动作 | 失败 / 分支 |
| --- | --- | --- |
| 1 | `goodsMapper.selectById(goodsId)` | null → `basicAck` 丢弃（非法消息） |
| 2 | `UPDATE goods SET stock_count = stock_count - 1 WHERE id = ? AND stock_count > 0` | 影响行数 ≤0 → `basicAck` 丢弃（确实没库存） |
| 3 | insert `order_info`：userId、goodsId、goodsName、count=1、price=seckillPrice、status=0、createTime=now | 异常 → catch → `basicNack(requeue=true)` |
| 4 | insert `seckill_order`：userId、goodsId、orderId=orderInfo.id、createTime=now | 唯一键冲突 → 异常 → NACK 重入队（现有实现有死循环风险，见 14 节） |
| 5 | Redis `seckill:result:{goodsId}:{userId}` = orderId，TTL 30min；同时 `seckill:order:{goodsId}:{userId}` = orderId，TTL 30min | Redis 异常 → NACK 重试；DB 已提交但 Redis 未写，轮询会一直是 -1 |
| 6 | `channel.basicAck(deliveryTag, false)` | ACK 成功，消息出队 |

> **消费者没有 @Transactional**
>
> 三步 DB 操作不是同一事务。若第 3 步成功、第 4 步失败并重试，会重复插入 `order_info`。这是当前代码的真实行为，复刻时保留并在文档中标注；只有用户要求“修复”时才加事务。

### 8.5 结果轮询流程

1. **前端发起**`GET /api/order/result/{goodsId}`，带 Token。
2. **拦截器取 userId**，与商品 ID 拼出 `seckill:result:{goodsId}:{userId}`。
3. **读 Redis**：有值 → `Long.valueOf` → `SeckillResultDTO(orderId)`；无值 → `-1`。
4. **前端继续轮询**直到 `orderId > 0` 或达到自定超时。后端不提供“取消排队”。
5. **结果过期**：30 分钟后 Key 消失，再查返回 -1；订单仍在 DB 中。

### 8.6 失败与补偿

| 失败点 | 系统行为 | 用户可见结果 | 一致性影响 |
| --- | --- | --- | --- |
| Redis 不可用 | Lua 执行异常 → 兜底异常 | `500` | 无扣减、无投递 |
| MQ 投递失败 | 执行 ROLLBACK_SCRIPT：库存 +1、删锁、删售罄标记 | `500` | 回到请求前状态 |
| 消费者查不到商品 | ACK 丢弃 | 轮询一直 -1 | Redis 已扣，DB 未扣 → 不一致 |
| 消费者 DB 扣库存失败 | ACK 丢弃 | 轮询一直 -1 | Redis 已扣，DB 未扣 → 不一致 |
| 消费者唯一键冲突 | NACK 重入队 | 轮询可能一直 -1 | 可能重复 order_info、无限重试 |
| 消费者 Redis 写结果失败 | NACK 重入队 | 轮询 -1，重试成功后变 orderId | DB 已扣，最终可恢复 |
| 应用重启 | 重新预热库存（覆盖）+ 清售罄标记 | 无直接感知 | 可能冲掉运行中的人工 Redis 调整 |

## 09 · 模块实现规格

按包逐个说明“这个类必须做什么、必须暴露什么、不能做什么”。关键类给出可直接照抄的代码骨架。

### 9.1 分层与依赖方向

**controller** HTTP 适配 → **service** 业务编排 → **mapper** MyBatis-Plus → **entity** 表映射 **mq** 异步通道 **common/config/interceptor** 横切能力

严格单向：Controller 不直接调 Mapper；Mapper 不依赖 Service；MQ 消费者可以调 Mapper；Common 不依赖业务包。

### 9.2 类职责表

| 类 | 关键方法 | 必须实现的行为 |
| --- | --- | --- |
| `AuthController` | `login(@Valid LoginDto)` | 返回 `Result<String>`；只调 UserService |
| `GoodsController` | `listGoods()`、`getGoods(Long)` | 返回 `Result<List<GoodsDTO>>` / `Result<GoodsDTO>` |
| `SeckillController` | `seckill(Long)`、`queryResult(Long)` | 从 `LoginInterceptor.getCurrentUserId()` 取用户；包 Result |
| `UserService` / `UserServiceImpl` | `String login(LoginDto)` | 查用户、MD5 比对、Sa-Token 登录、返回 token |
| `GoodsService` / `GoodsServiceImpl` | `List<GoodsDTO> listGoods()`、`GoodsDTO getGoodsById(Long)` | 无缓存；null 抛 PARAM_ERROR |
| `SeckillService` / `SeckillServiceImpl` | `initStock()`、`String seckill(Long,Long)`、`SeckillResultDTO queryResult(Long,Long)` | 预热、Lua、MQ、补偿、轮询；全部核心逻辑在这里 |
| `GoodsMapper` | `decrStock(Long)` | `@Update` 条件扣减；返回影响行数 |
| `OrderInfoMapper` / `SeckillOrderMapper` / `UserMapper` | 继承 `BaseMapper` | 不写 XML；使用 MP 默认 CRUD |
| `SeckillProducer` | `send(SeckillMessage)` | 指定 exchange + routingKey；打日志 |
| `SeckillConsumer` | `handleSeckillMessage(msg, Channel, deliveryTag)` | 落库 5 步 + 手动 ACK/NACK |
| `LoginInterceptor` | `preHandle`、`afterCompletion`、`static getCurrentUserId()` | Bearer 解析、Sa-Token 校验、ThreadLocal 清理 |
| `RateLimitInterceptor` | `preHandle` | Redis INCR + EXPIRE，5 秒 5 次 |

### 9.3 公共层代码骨架

<!-- common/Result.java -->
```java
@Data
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(CodeMsg.SUCCESS.getCode(), CodeMsg.SUCCESS.getMsg(), data);
    }

    public static <T> Result<T> error(CodeMsg codeMsg) {
        return new Result<>(codeMsg.getCode(), codeMsg.getMsg(), null);
    }
}
```

<!-- common/RedisTypeString.java · key 拼接 -->
```java
public enum RedisTypeString {
    STOCK_PREFIX("seckill:stock:"),
    SOLD_OUT_PREFIX("seckill:soldout:"),
    USER_ORDER_PREFIX("seckill:order:"),
    RESULT_PREFIX("seckill:result:");

    private final String value;

    RedisTypeString(String value) { this.value = value; }

    public String key(Object... parts) {
        StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append(':');
            builder.append(parts[i]);
        }
        return builder.toString();
    }
}
```

`key(...)` 用冒号连接。调用示例：`STOCK_PREFIX.key(1)` → `seckill:stock:1`；`USER_ORDER_PREFIX.key(goodsId, userId)` → `seckill:order:{goodsId}:{userId}`。

### 9.4 数据访问层

<!-- mapper/GoodsMapper.java -->
```java
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    @Update("UPDATE goods SET stock_count = stock_count - 1 " +
            "WHERE id = #{goodsId} AND stock_count > 0")
    int decrStock(@Param("goodsId") Long goodsId);
}
```

| 实体 | 表名 | 主键策略 | 字段映射 |
| --- | --- | --- | --- |
| `User` | `user` | `@TableId(type = IdType.AUTO)` | 驼峰自动映射，无需 `@TableField` |
| `Goods` | `goods` | AUTO | 同上 |
| `OrderInfo` | `order_info` | AUTO | 同上 |
| `SeckillOrder` | `seckill_order` | AUTO | 同上 |

### 9.5 秒杀服务核心代码

以下是必须逐行为一致的实现。省略 import 与日志，保留全部判断顺序、Key 顺序、返回码与补偿逻辑。

<!-- service/Impl/SeckillServiceImpl.java · 核心 -->
```java
private static final long LOCK_TTL_SECONDS = 30 * 60;

private static final DefaultRedisScript<Long> SECKILL_SCRIPT =
    new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[2]) == 1 then
            return 2
        end
        local stock = tonumber(redis.call('GET', KEYS[1]))
        if stock == nil or stock <= 0 then
            redis.call('SET', KEYS[2], '1')
            return 3
        end
        local locked = redis.call('SET', KEYS[3], '1', 'NX', 'EX', tonumber(ARGV[1]))
        if not locked then
            return 4
        end
        local left = redis.call('DECR', KEYS[1])
        if left == 0 then
            redis.call('SET', KEYS[2], '1')
        end
        return 1
        """, Long.class);

private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT =
    new DefaultRedisScript<>("""
        redis.call('INCR', KEYS[1])
        redis.call('DEL', KEYS[2])
        redis.call('DEL', KEYS[3])
        return 1
        """, Long.class);

@Override
@PostConstruct
public void initStock() {
    List<Goods> goodsList = goodsMapper.selectList(null);
    for (Goods goods : goodsList) {
        String stockKey = RedisTypeString.STOCK_PREFIX.key(goods.getId());
        String soldOutKey = RedisTypeString.SOLD_OUT_PREFIX.key(goods.getId());
        stringRedisTemplate.opsForValue()
                .set(stockKey, String.valueOf(goods.getStockCount()));
        stringRedisTemplate.delete(soldOutKey);
    }
}

@Override
public String seckill(Long userId, Long goodsId) {
    Goods goods = goodsMapper.selectById(goodsId);
    if (goods == null) throw new GlobalException(CodeMsg.PARAM_ERROR);

    LocalDateTime now = LocalDateTime.now();
    if (goods.getStartTime().isAfter(now))
        throw new GlobalException(CodeMsg.SECKILL_NOT_STARTED);
    if (goods.getEndTime().isBefore(now))
        throw new GlobalException(CodeMsg.SECKILL_ENDED);

    String stockKey   = RedisTypeString.STOCK_PREFIX.key(goodsId);
    String soldOutKey = RedisTypeString.SOLD_OUT_PREFIX.key(goodsId);
    String lockKey    = RedisTypeString.USER_ORDER_PREFIX.key(goodsId, userId);

    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            List.of(stockKey, soldOutKey, lockKey),
            String.valueOf(LOCK_TTL_SECONDS));

    if (result == null) throw new GlobalException(CodeMsg.SERVER_ERROR);
    if (result == 2L) throw new GlobalException(CodeMsg.SECKILL_SOLD_OUT);
    if (result == 3L) throw new GlobalException(CodeMsg.STOCK_EMPTY);
    if (result == 4L) throw new GlobalException(CodeMsg.SECKILL_REPEATED);

    try {
        seckillProducer.send(new SeckillMessage(userId, goodsId));
    } catch (Exception e) {
        stringRedisTemplate.execute(
                ROLLBACK_SCRIPT, List.of(stockKey, lockKey, soldOutKey));
        throw new GlobalException(CodeMsg.SERVER_ERROR);
    }
    return "购买成功!正在排队...";
}

@Override
public SeckillResultDTO queryResult(Long userId, Long goodsId) {
    String resultKey = RedisTypeString.RESULT_PREFIX.key(goodsId, userId);
    String result = stringRedisTemplate.opsForValue().get(resultKey);
    if (StringUtils.hasText(result)) {
        return new SeckillResultDTO(Long.valueOf(result));
    }
    return new SeckillResultDTO(-1L);
}
```

### 9.6 消费者核心代码

<!-- mq/SeckillConsumer.java · 核心 -->
```java
@RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE, ackMode = "MANUAL")
public void handleSeckillMessage(SeckillMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    Long userId = message.getUserId();
    Long goodsId = message.getGoodsId();
    try {
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        int rows = goodsMapper.decrStock(goodsId);
        if (rows <= 0) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setUserId(userId);
        orderInfo.setGoodsId(goodsId);
        orderInfo.setGoodsName(goods.getName());
        orderInfo.setGoodsCount(1);
        orderInfo.setGoodsPrice(goods.getSeckillPrice());
        orderInfo.setStatus(0);
        orderInfo.setCreateTime(LocalDateTime.now());
        orderInfoMapper.insert(orderInfo);

        SeckillOrder seckillOrder = new SeckillOrder();
        seckillOrder.setUserId(userId);
        seckillOrder.setGoodsId(goodsId);
        seckillOrder.setOrderId(orderInfo.getId());
        seckillOrder.setCreateTime(LocalDateTime.now());
        seckillOrderMapper.insert(seckillOrder);

        String resultKey = RedisTypeString.RESULT_PREFIX.key(goodsId, userId);
        stringRedisTemplate.opsForValue()
                .set(resultKey, String.valueOf(orderInfo.getId()), 30, TimeUnit.MINUTES);

        String lockKey = RedisTypeString.USER_ORDER_PREFIX.key(goodsId, userId);
        stringRedisTemplate.opsForValue()
                .set(lockKey, String.valueOf(orderInfo.getId()), 30, TimeUnit.MINUTES);

        channel.basicAck(deliveryTag, false);
    } catch (Exception e) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            // log
        }
    }
}
```

### 9.7 拦截器核心代码

<!-- interceptor/LoginInterceptor.java -->
```java
private static final ThreadLocal<Long> USER_THREAD_LOCAL = new ThreadLocal<>();

@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                         Object handler) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw new GlobalException(CodeMsg.UNAUTHORIZED);
    }
    String token = authHeader.substring(7);
    Object loginIdObj = StpUtil.getLoginIdByToken(token);
    if (loginIdObj == null) {
        throw new GlobalException(CodeMsg.UNAUTHORIZED);
    }
    USER_THREAD_LOCAL.set(Long.valueOf(loginIdObj.toString()));
    return true;
}

@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                            Object handler, Exception ex) {
    USER_THREAD_LOCAL.remove();
}

public static Long getCurrentUserId() {
    return USER_THREAD_LOCAL.get();
}
```

<!-- interceptor/RateLimitInterceptor.java · 核心 -->
```java
private static final int MAX_COUNT = 5;
private static final int WINDOW_SIZE = 5;
private static final String REDIS_KEY_PREFIX = "ratelimit:seckill:";

@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                         Object handler) {
    Long currentUserId = LoginInterceptor.getCurrentUserId();
    if (currentUserId == null) return true;

    String redisKey = REDIS_KEY_PREFIX + currentUserId;
    Long count = stringRedisTemplate.opsForValue().increment(redisKey);
    if (count != null && count == 1) {
        stringRedisTemplate.expire(redisKey, WINDOW_SIZE, TimeUnit.SECONDS);
    }
    if (count != null && count > MAX_COUNT) {
        throw new GlobalException(CodeMsg.RATE_LIMIT);
    }
    return true;
}
```

### 9.8 用户与商品服务

<!-- service/Impl/UserServiceImpl.java · login -->
```java
public String login(LoginDto loginDto) {
    User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getPhone, loginDto.getPhone()));
    if (user == null) throw new GlobalException(CodeMsg.USER_NOT_FOUND);

    String raw = user.getSalt() + loginDto.getPassword();
    String inputHash = DigestUtils.md5DigestAsHex(
            raw.getBytes(StandardCharsets.UTF_8));
    if (!inputHash.equals(user.getPassword()))
        throw new GlobalException(CodeMsg.PASSWORD_ERROR);

    StpUtil.login(user.getId());
    return StpUtil.getTokenValue();
}
```

<!-- dto/GoodsDTO.java · from -->
```java
public static GoodsDTO from(Goods goods) {
    GoodsDTO dto = new GoodsDTO();
    dto.setId(goods.getId());
    dto.setName(goods.getName());
    dto.setTitle(goods.getTitle());
    dto.setImage(goods.getImage());
    dto.setPrice(goods.getPrice());
    dto.setSeckillPrice(goods.getSeckillPrice());
    dto.setStockCount(goods.getStockCount());
    dto.setStartTime(goods.getStartTime());
    dto.setEndTime(goods.getEndTime());

    LocalDateTime now = LocalDateTime.now();
    if (now.isBefore(goods.getStartTime())) {
        dto.setSeckillStatus(0);
    } else if (now.isAfter(goods.getEndTime())) {
        dto.setSeckillStatus(2);
    } else {
        dto.setSeckillStatus(1);
    }
    return dto;
}
```

### 9.9 编码约定

- 构造器注入：类上 `@RequiredArgsConstructor`，依赖字段 `private final`。
- Lombok：实体/DTO 用 `@Data @NoArgsConstructor @AllArgsConstructor`；日志用 `@Slf4j`。
- 金额一律 `BigDecimal`；时间一律 `LocalDateTime`。
- Mapper 只继承 `BaseMapper`，不写 XML；复杂 SQL 用 `@Update` 注解。
- Controller 不写业务判断；Service 抛 `GlobalException`；异常统一由 `GlobalExceptionHandler` 转 JSON。
- Redis Key 不允许硬编码字符串拼接，必须走 `RedisTypeString`（限流 Key 是唯一例外，直接拼前缀）。

## 10 · 状态与边界

秒杀系统的 bug 多半不在主流程，而在“刚好卖完”“刚好重复”“刚好消息失败”这些边界。复刻时逐条对照。

### 10.1 秒杀结果矩阵

| 条件 | code | msg | 是否投递 MQ | Redis 变化 |
| --- | --- | --- | --- | --- |
| 商品不存在 | 500101 | 参数校验失败 | 否 | 无 |
| 未开始 | 500204 | 秒杀未开始 | 否 | 无 |
| 已结束 | 500205 | 秒杀已结束 | 否 | 无 |
| 售罄标记存在 | 500203 | 已售罄 | 否 | 无 |
| 库存 ≤0 / Key 不存在 | 500201 | 库存不足 | 否 | 写售罄标记 |
| 防重锁已存在 | 500202 | 请勿重复购买 | 否 | 无 |
| 限流超限 | 500206 | 操作过于频繁，请稍后再试 | 否 | 计数 +1 |
| 成功 | 0 | success | 是 | 库存 -1；写防重锁；可能写售罄标记 |
| MQ 投递异常 | 500 | 服务器异常 | 尝试失败 | 库存 +1；删锁；删售罄标记 |

### 10.2 Redis 状态机

**Preheated** stock=N；无售罄标记 → **Request** Lua 执行 → **Success** stock=N-1；lock 写入 → **Stock=0** 同时写售罄标记

**SoldOut** 后续请求直接返回 2 → **Rollback** MQ 失败：stock+1、删锁、删售罄 → **Back** 回到可售状态（若库存仍 >0）

- 售罄标记一旦写入，只有 ROLLBACK_SCRIPT 或应用重启（`initStock`）会删除。
- 防重锁 TTL 30 分钟；消费者会把值改成 orderId，但 TTL 仍是 30 分钟。
- 库存 Key 没有 TTL；应用重启会覆盖为 DB 当前值。

### 10.3 请求边界清单

| 边界 | 期望行为 | 验证方式 |
| --- | --- | --- |
| 无 `Authorization` | `500102`，不进入业务 | curl 不带头 |
| 头是 `Bearer` 但没有 token | `500102` | `Authorization: Bearer` |
| Token 过期 / 伪造 | `500102` | 随机字符串 |
| 同一用户并发 10 次秒杀 | 1 次成功或排队；其余按 Lua 返回（重复/库存） | JMeter 单用户 |
| 100 个不同用户抢 100 库存 | 100 条订单；第 101 个 `500203/500201` | 压测 + SQL |
| 轮询在消费者完成前 | `{orderId:-1}` | 秒杀后立即查 |
| 轮询在 30 分钟后 | `{orderId:-1}`（结果 Key 过期） | 等待或手动 DEL |
| 商品 2 秒杀（库存 200） | 与商品 1 独立 Key，互不影响 | 分别秒杀 |
| 秒杀价 0.01 | 订单 `goods_price=0.01` | 查 order_info |
| 应用重启后立即秒杀 | 库存已预热；售罄标记已清除 | 查 Redis Key |

### 10.4 并发不变量

- **不超卖：**`COUNT(order_info WHERE goods_id=1) ≤ 100`。
- **不重复：**`seckill_order` 的 `(user_id, goods_id)` 唯一。
- **Redis 与 DB 对账：**同一轮运行内，`Redis 剩余 + order_info 条数 = 初始库存`（若消费者失败则可能不等，这是已知风险）。
- **锁与结果同序：**两者 Key 都是 `{goodsId}:{userId}`，不要混用顺序。
- **线程安全：**ThreadLocal 必须在 `afterCompletion` 清理，否则线程池复用会串用户。

## 11 · 前端附录（未入库，P1）

当前仓库没有 `FlashSaleFrontend/`。本节只根据 README、OUTLINE、GUIDE 中明确写到的内容整理；未写到的部分标记为假设，复刻者可按需调整。

> **先确认范围**
>
> 如果用户只要“复刻当前仓库”，**跳过本节**。如果用户要“复刻 README 描述的完整项目”，再按本节补建，并明确告诉用户：前端实现属于补全，不是仓库现状。

### 11.1 工程与依赖

- Vue 3.5 + Vue Router 4 + Axios + Vite；`npm create vite@latest FlashSaleFrontend -- --template vue`。
- 依赖：`vue-router`、`axios`；开发端口默认 5173。
- 后端无 CORS 配置，必须在 `vite.config.js` 配 `/api` 代理到 `http://localhost:8080`，或由用户明确授权后加 CORS。

### 11.2 目录与路由

<!-- frontend tree · 建议结构（假设） -->
```text
FlashSaleFrontend/
├── package.json
├── vite.config.js
├── index.html
└── src/
    ├── main.js
    ├── App.vue
    ├── router/index.js
    ├── api/
    │   ├── request.js          # Axios 实例 + Token + 统一解包
    │   ├── auth.js             # login
    │   ├── goods.js            # listGoods / getGoods
    │   └── seckill.js          # seckill / queryResult
    ├── components/
    │   ├── NavBar.vue
    │   └── CountDown.vue
    └── views/
        ├── LoginView.vue
        ├── GoodsList.vue
        └── OrderResult.vue
```

| 路由 | 组件 | 行为 |
| --- | --- | --- |
| `/` | 重定向 | 自动跳 `/goods` |
| `/login` | `LoginView.vue` | 手机号 + 密码表单；成功存 `localStorage.token`；跳 `/goods` |
| `/goods` | `GoodsList.vue` | 拉商品列表；渲染卡片；秒杀按钮 + 倒计时；点击进入 `/order/:goodsId` |
| `/order/:goodsId` | `OrderResult.vue` | 调秒杀接口；每 500ms 轮询结果；展示排队中 / 成功订单号 / 失败原因 |

### 11.3 Axios 封装（GUIDE 给出的原文）

<!-- src/api/request.js -->
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

注意：响应拦截器直接返回 `body.data`，所以页面里写 `const list = await listGoods()`，不要再取一次 `.data`。

### 11.4 页面行为规格

#### LoginView（文档事实）

表单提交 phone/password → `POST /api/user/login` → 把返回 token 写入 `localStorage.token` → `router.push('/goods')`。失败时展示 `msg`。

#### GoodsList（文档事实）

调用 `GET /api/goods`；每个商品卡片展示名称、秒杀价、原价、库存、倒计时；`seckillStatus` 控制按钮文案：未开始/立即秒杀/已结束。

#### OrderResult（文档事实）

进入页面即调用 `POST /api/seckill/{goodsId}`；成功提示“正在排队”；随后每 500ms 调 `GET /api/order/result/{goodsId}`；`orderId > 0` 停止轮询并展示订单号。

#### 倒计时与超时（假设）

CountDown 依据 `startTime/endTime` 计算；轮询超时时间仓库未定义，建议 30 秒后提示“仍在排队，请稍后在订单页查看”。这不是代码事实。

### 11.5 前端必须避免

- 不要把 `baseURL` 写成相对路径又忘配代理——后端无 CORS，会直接跨域失败。
- 不要用 `res.data.data` 双重解包；拦截器已解一层。
- 不要在轮询时忽略业务错误；`500102` 应跳登录页。
- 商品图路径 `/images/*.jpg` 在仓库中没有对应静态资源；补建前端时需要准备占位图或替换路径。

## 12 · 压测规格

JMeter 不是“附加项”，它是验证秒杀正确性的验收工具。脚本结构、占位符、校验 SQL 都要复刻。

### 12.1 JMX 测试计划结构

| 元素 | 类型 | 关键配置 |
| --- | --- | --- |
| Test Plan | 根节点 | 名称“FlashSale 秒杀压测计划”；变量 `BASE_URL=localhost:8080`、`GOODS_ID=1` |
| HTTP 请求默认值 | Config | domain `${BASE_URL}`，protocol http，UTF-8 |
| 01-登录获取Token | SetupThreadGroup | `@@SETUP_THREADS@@` 线程 / `@@SETUP_RAMPUP@@` 秒 |
| 用户数据CSV | CSVDataSet | `users.csv`，变量 `phone,password` |
| POST /api/user/login | HTTPSampler | Body `{"phone":"${phone}","password":"${password}"}`；Content-Type JSON |
| 提取Token | JSONPostProcessor | `$.data` → 变量 `token` |
| Token写入属性 | BeanShellPreProcessor | 写入 `global_token_{threadNum%500}` |
| 02-秒杀请求（主压测） | ThreadGroup | `@@THREADS@@` / `@@RAMPUP@@` / `@@DURATION@@`，scheduler=true |
| 随机取Token | BeanShellPreProcessor | 取 `global_token_{threadNum%500}` → `thread_token` |
| POST /api/seckill/{goodsId} | HTTPSampler | Path `/api/seckill/${GOODS_ID}`；Authorization `Bearer ${thread_token}` |
| GET /api/order/result/{goodsId} | HTTPSampler | Path `/api/order/result/${GOODS_ID}`；同一 Authorization |
| 间隔200ms | ConstantTimer | delay 200 |
| 监听器 | ResultCollector | 察看结果树、聚合报告、汇总报告、图形结果 |

<!-- JMX · BeanShell 片段（两段必须一致） -->
```xml
# 登录线程组：写入全局属性
String t = vars.get("token");
if (t != null && !t.isEmpty()) {
    props.put("global_token_" + (ctx.getThreadNum() % 500), t);
}

# 压测线程组：读取
int idx = ctx.getThreadNum() % 500;
String t = props.get("global_token_" + idx);
if (t != null && !t.isEmpty()) {
    vars.put("thread_token", t);
} else {
    log.warn("未找到Token idx=" + idx);
}
```

### 12.2 一键脚本与占位符

`run-test.ps1` 读取 JMX 文本，替换以下占位符后写到 `results/flashsale-temp.jmx`，并修正 CSV 为绝对路径：

| 占位符 | 来源 | 说明 |
| --- | --- | --- |
| `@@THREADS@@` | 压测档位 | 主线程组线程数 |
| `@@RAMPUP@@` | 压测档位 | 主线程组 ramp-up 秒 |
| `@@DURATION@@` | 压测档位 | 主线程组持续秒 |
| `@@SETUP_THREADS@@` | `min(THREADS, 500)` | 登录线程数，最多 500 |
| `@@SETUP_RAMPUP@@` | 固定 5 | 登录预热 ramp-up |
| `>users.csv<` | 脚本目录 | 替换为绝对路径，避免临时 JMX 找不到 CSV |

| 档位 | 线程 / 时长 | ramp-up | 用途 |
| --- | --- | --- | --- |
| Light | 50 / 30s | 5s | 冒烟 |
| Medium | 200 / 60s | 10s | 默认推荐 |
| Heavy | 500 / 120s | 10s | 找瓶颈 |
| Custom | 任意 | 任意 | 手工输入 |

### 12.3 数据准备与校验

<!-- jmeter/prepare.sql · 核心 -->
```sql
USE flashsale;

DELETE FROM seckill_order WHERE user_id > 2;
DELETE FROM order_info    WHERE user_id > 2;
DELETE FROM user          WHERE id > 2;
ALTER TABLE user AUTO_INCREMENT = 3;

INSERT INTO user (phone, password, salt, nickname)
SELECT
    CONCAT('138', LPAD(n, 8, '0')),
    '16add37301c95de2bc7abe1788a752e7',
    '1a2b3c4d',
    CONCAT('PT', n)
FROM (
    SELECT (@row := @row + 1) AS n
    FROM information_schema.columns a,
         information_schema.columns b,
         (SELECT @row := 2) r
    LIMIT 500
) t;
```

<!-- 校验 SQL -->
```sql
-- 1) 不超卖：stock_count 与订单数对比
SELECT g.id, g.stock_count, COUNT(o.id) AS orders,
       CASE WHEN g.stock_count >= COUNT(o.id)
            THEN 'OK' ELSE 'OVERSELL' END AS result
FROM goods g
LEFT JOIN order_info o ON o.goods_id = g.id
WHERE g.id = 1
GROUP BY g.id, g.stock_count;

-- 2) 不重复：应为 0
SELECT COUNT(*) FROM (
  SELECT user_id, goods_id
  FROM seckill_order
  GROUP BY user_id, goods_id
  HAVING COUNT(*) > 1
) t;

-- 3) Redis 剩余库存
-- redis-cli GET seckill:stock:1
```

### 12.4 已产出的报告数据（注意来源）

| 事务 | 样本数 | 平均响应 | 吞吐 | 错误率 |
| --- | --- | --- | --- | --- |
| POST /api/seckill/1 | 4250 | 23.60 ms | 140.74 /s | 0.00% |
| GET /api/order/result/1 | 4250 | 4.01 ms | 140.64 /s | 0.00% |
| Total | 8500 | 13.81 ms | 279.53 /s | 0.00% |

> **报告不是真实压测**
>
> `jmeter/results/report/` 由 `simulate.ps1` 生成，JTL 是脚本随机造的模拟数据（8500 条）。它可以用来演示报告结构，**不能作为性能结论**。真实压测必须用 `run-test.ps1` 连后端跑。

### 12.5 脚本中的机器相关路径

- `run-test.ps1` 与 `simulate.ps1` 都硬编码 `D:\jmeter\apache-jmeter-5.6.3\bin`。复刻者必须改成自己的 JMeter 路径，或加入 PATH。
- `run-test.ps1` 要求 `MYSQL_PASSWORD` 已设置；`mysql`、`redis-cli` 必须在 PATH。
- `users.csv` 只有 2 个用户；真实压测前先跑 `prepare.sql` 造 500 个用户，并把 CSV 扩充或由脚本生成。

## 13 · 验收标准

复刻完成后，逐项执行。任何一项失败，都不算“一比一”。

### 13.1 必过清单

| ID | 检查项 | 执行方式 | 通过条件 |
| --- | --- | --- | --- |
| A1 | 编译通过 | `.\mvnw.cmd -DskipTests package` | BUILD SUCCESS；jar 生成 |
| A2 | 应用启动 + 库存预热 | 设置 `MYSQL_PASSWORD` 后启动 | 日志出现 `Started ...`；`redis-cli GET seckill:stock:1` 返回 `100` |
| A3 | 登录成功 | POST login，正确账号 | `code=0`，`data` 非空字符串 |
| A4 | 登录失败 | 错误密码 / 不存在手机号 | `500302` / `500301` |
| A5 | 商品列表 | GET `/api/goods` | 6 条；字段齐全；`seckillStatus` 为 0/1/2 之一 |
| A6 | 商品详情 | GET `/api/goods/1` | 返回 iPhone 16 Pro；不存在 ID 返回 `500101` |
| A7 | 首次秒杀 | 带 Token POST `/api/seckill/1` | `code=0`，`data="购买成功!正在排队..."` |
| A8 | 重复秒杀 | 同一用户立即再秒杀 | `500202` |
| A9 | 轮询结果 | GET result，等消费者完成 | 先 `-1`，后 `>0` |
| A10 | DB 订单落库 | 查 `order_info` / `seckill_order` | 订单数与成功次数一致；成交价 0.01；status 0 |
| A11 | 限流 | 同一用户 5 秒内第 6 次秒杀 | `500206`（若前 5 次都被业务拒绝，仍应触发限流） |
| A12 | 未登录拦截 | 不带 Token 秒杀/轮询 | `500102` |
| A13 | 不超卖 | 准备 500 用户压测商品 1 | 订单数 ≤ 100；SQL 校验 OK |
| A14 | 不重复 | 重复校验 SQL | 返回 0 |
| A15 | JMeter 可运行 | `run-test.ps1` 或 CLI | 生成 JTL + HTML 报告，无脚本解析错误 |
| A16 | 文件树一致 | 对比第 2 节清单 | 类名、包名、文件路径一致 |
| A17 | 差异已标注 | 对照第 14 节 | 已知偏差未被“悄悄修复”；或修复项已获用户确认 |

### 13.2 关键命令（可直接复制）

<!-- acceptance · PowerShell -->
```powershell
$env:MYSQL_PASSWORD = "你的密码"
cd FlashSaleBackend
.\mvnw.cmd -DskipTests package

# 启动（另开窗口）
.\mvnw.cmd spring-boot:run

# 登录并保存 token
$r = Invoke-RestMethod -Method Post http://localhost:8080/api/user/login `
  -ContentType "application/json" `
  -Body '{"phone":"13800000001","password":"123456"}'
$token = $r.data

# 秒杀 + 轮询
Invoke-RestMethod -Method Post "http://localhost:8080/api/seckill/1" `
  -Headers @{ Authorization = "Bearer $token" }
Start-Sleep -Milliseconds 800
Invoke-RestMethod "http://localhost:8080/api/order/result/1" `
  -Headers @{ Authorization = "Bearer $token" }

# Redis 与 DB 对账
redis-cli GET seckill:stock:1
mysql -uroot -p -e "USE flashsale; SELECT COUNT(*) FROM order_info WHERE goods_id=1;"
mysql -uroot -p -e "USE flashsale; SELECT COUNT(*) FROM (SELECT user_id,goods_id FROM seckill_order GROUP BY user_id,goods_id HAVING COUNT(*)>1) t;"
```

### 13.3 完成定义（Definition of Done）

- A1–A17 全部通过，或失败项已明确记录原因。
- 第 2 节文件树完整；没有新增未授权文件。
- 第 4 节 DDL 与种子数据逐字执行成功。
- 第 5 节 5 个接口的响应示例与真实响应一致。
- 第 7 节 Lua 脚本、Redis Key、MQ 名称与代码一致。
- 第 14 节差异表已交付；没有“静默优化”。
- 第 15 节 AI 复刻提示词可直接交给下一个执行者。

## 14 · 文档与代码差异、已知风险

这一节是“诚实清单”。复刻时最容易踩的坑，是照着 README/OUTLINE 写，而不是照着代码写。

### 14.1 文档描述 vs 代码事实

| # | 主题 | 文档说法 | 代码事实 | 复刻指令 |
| --- | --- | --- | --- | --- |
| 1 | 库存扣减 | README/OUTLINE：Redis `DECR`，失败 `INCR` 回滚；`SETNX` 防重 | 一段 Lua 原子完成售罄判断、库存判断、`SET NX`、`DECR`；只有 MQ 失败才走回滚脚本 | 以 Lua 为准 |
| 2 | Redis Key 顺序 | README：`seckill:user:{userId}:{goodsId}` | `seckill:order:{goodsId}:{userId}`；结果 Key 同序 | 商品在前、用户在后 |
| 3 | 前端 | README/OUTLINE：存在 `FlashSaleFrontend` | 仓库没有该目录；GUIDE 只给 request.js 与路由 | 确认范围后再补 |
| 4 | 商品缓存 | OUTLINE：商品列表 Redis 缓存 TTL 60s | `GoodsServiceImpl` 直接查 DB，无缓存 | 不要加缓存 |
| 5 | 并行预热 | OUTLINE：`CompletableFuture` 并行加载 | `for` 循环顺序预热 | 顺序循环 |
| 6 | 表数量 | OUTLINE：3 张表 | `schema.sql` 有 4 张（含 `user`） | 4 张 |
| 7 | 参数校验 | CodeMsg 有 `PARAM_ERROR 500101` | `@Valid` 失败未被显式处理，兜底为 `500` | 保留现状或明确修复 |
| 8 | 消费者事务 | GUIDE 风险表已指出没有 `@Transactional` | 确实没有；三步 DB 操作可部分成功 | 现状复刻；修复需授权 |
| 9 | 重复消费 | GUIDE 风险表：唯一键冲突会无限重试 | `catch Exception` → `basicNack(requeue=true)`，无 `DuplicateKeyException` 特判 | 现状复刻；修复需授权 |
| 10 | 防重锁 TTL | GUIDE：30 分钟后可能重复购买 | 消费者把锁值覆盖为 orderId，TTL 30 分钟 | 现状复刻 |
| 11 | 结果 TTL | GUIDE：30 分钟后轮询 -1 | 结果 Key `set(..., 30, MINUTES)` | 现状复刻 |
| 12 | 启动预热 | GUIDE 风险表：不会清理旧售罄标记 | 实际会 `delete(soldOutKey)` | 以代码为准：会清理 |
| 13 | 未使用 Bean | OUTLINE 提到线程池/并行 | `RedisTemplate`、`seckillExecutor` 定义但未使用；`spring-session-data-redis` 未使用 | 保留脚手架，不删不改 |
| 14 | 压测报告 | README：历史压测报告 | 由 `simulate.ps1` 生成的模拟 JTL | 标注为模拟，不引用为性能结论 |
| 15 | RabbitMQ 镜像 | OUTLINE：`rabbitmq:3-management` | `docker-compose.yml`：`rabbitmq:management` | 用 compose 的标签 |
| 16 | 商品详情接口 | README API 表未列 | `GET /api/goods/{goodsId}` 存在 | 保留 |
| 17 | JMeter 路径 | 脚本自称“一键” | 硬编码 `D:\jmeter\apache-jmeter-5.6.3\bin` | 改成目标机器路径 |
| 18 | CORS | 无相关文档 | 后端无 CORS 配置 | 前端用代理，或授权后加 |

### 14.2 代码中的真实风险

| # | 风险 | 触发条件 | 后果 |
| --- | --- | --- | --- |
| R1 | 消费者无事务 | 扣库存成功、insert 订单/秒杀订单失败 | 库存已扣但无订单；数据不一致 |
| R2 | 唯一键冲突重试死循环 | 同一用户重复消息 / 重复消费 | NACK requeue → 再次冲突 → 无限循环；每次还多插一条 `order_info` |
| R3 | 防重锁 30 分钟过期 | 用户 30 分钟后再次秒杀同一商品且有库存 | 可再次成功，破坏“一人一单” |
| R4 | 结果 Key 30 分钟过期 | 用户 30 分钟后轮询 | 返回 -1，误以为没抢到 |
| R5 | 启动覆盖库存 | 运行中人工调整 Redis 后重启 | 库存被 DB 值覆盖，售罄标记被清 |
| R6 | 消费者失败只 ACK 丢弃 | 商品不存在 / DB 扣减 0 行 | Redis 已扣、DB 未扣，库存“凭空消失” |
| R7 | MQ 默认 Java 序列化 | 消息类字段变更 / 跨版本 | 反序列化失败；消息无法消费 |
| R8 | ThreadLocal 泄漏 | 忘记 `afterCompletion.remove()` | 线程复用拿到旧 userId，越权 |
| R9 | Redis 单点 | Redis 宕机 | 秒杀、限流、轮询全部失败 |
| R10 | 限流固定窗口 | 窗口切换瞬间 | 可能出现短时双倍请求 |

> **修复原则**
>
> 以上是“学习项目故意保留/尚未处理”的真实问题。**默认复刻现状**。若用户要求生产化，按优先级修：R1 加事务、R2 特判唯一键冲突并 ACK、R3 防重标记不设 TTL 或加长、R4 结果 TTL 加长、R6 失败补偿。每修一项都要更新本文档，不能静默改变行为。

### 14.3 文档自身的维护规则

- 代码变更后，先改本文档的“代码事实”条目，再改代码；或代码与文档同一提交更新。
- 新增接口/表/Key 必须同步更新第 4、5、7 节。
- 任何“优化”都要在第 14 节登记，写明是否改变可观察行为。

## 15 · AI 复刻执行手册

把下面这段提示词连同本文档交给 AI。它按“先契约、后骨架、再核心、最后验收”的顺序执行，避免一上来就写业务导致返工。

### 15.1 可复制的启动提示词

<!-- prompt · 直接复制给 AI -->
```text
你是一名资深 Java 后端工程师。请在空目录中从零复刻一个名为 FlashSale 的高并发秒杀系统。
唯一需求文档是随附的《FlashSale-开发说明书》。你的目标不是“做一个类似的秒杀系统”，
而是“一比一复刻文档描述的项目”：文件树、包名、类名、DDL、接口、错误码、Redis Key、
Lua 脚本、RabbitMQ 拓扑、JMeter 脚本都必须一致。

硬性约束：
1. 技术栈：Java 21 + Spring Boot 4.1.0 + MyBatis-Plus 3.5.12 + MySQL 8 + Redis +
   RabbitMQ + Sa-Token 1.44.0；不要替换为 JPA、Redisson、Spring Security、Kafka。
2. 包名必须是 com.ftllight.flashsalebackend；分层必须是 controller/service/mapper/
   entity/dto/config/interceptor/mq/common，不得合并或重命名。
3. 秒杀资格判定必须使用文档第 7.3 节的 Lua 脚本，返回码 1/2/3/4 语义不可变；
   不要改成多条 Redis 命令，也不要用 Redisson 分布式锁。
4. 一人一单的 Redis Key 必须是 seckill:order:{goodsId}:{userId}；
   结果 Key 必须是 seckill:result:{goodsId}:{userId}；TTL 30 分钟。
5. 消费者必须 @RabbitListener(ackMode="MANUAL")，成功 basicAck，异常 basicNack(requeue=true)；
   不要擅自加 @Transactional 或死信队列。
6. 业务异常的 HTTP 状态仍是 200，响应体 code 非 0；不要改成 4xx/5xx。
7. 不新增注册、支付、后台管理、Swagger、CORS、缓存商品列表等文档未列出的功能。
8. 不“优化”文档第 14 节列出的已知风险；如需修复，先向我确认并单独记录。

执行顺序：
第 1 步：创建 Maven 工程、pom.xml、application.yml、目录结构。
第 2 步：写 schema.sql（4 张表 + 种子数据），本地执行并确认 3 商品 / 2 用户。
第 3 步：实现 common（Result/CodeMsg/GlobalException/Handler/RedisTypeString）。
第 4 步：实现 entity、mapper、dto。
第 5 步：实现 config（Redis/RabbitMQ/Web/ThreadPool）。
第 6 步：实现 UserService + AuthController，验证登录返回 Token。
第 7 步：实现 GoodsService + GoodsController，验证 6 条商品与 seckillStatus。
第 8 步：实现 SeckillServiceImpl（预热、Lua、MQ 投递、补偿、轮询）。
第 9 步：实现 SeckillProducer、SeckillConsumer、拦截器。
第 10 步：启动 MySQL/Redis/RabbitMQ，跑通登录→秒杀→轮询→DB 落库。
第 11 步：补 JMeter 脚本、数据准备脚本、验收 SQL。
第 12 步：按文档第 13 节 A1–A17 逐项验收，输出通过/失败清单与证据。

每完成一步，先自检再继续；不要等全部写完才测试。
如果文档与 README 冲突，以文档第 14 节的“代码事实”为准。
最终交付：完整源码 + schema.sql + application.yml + jmeter/ + 一份验收报告。
```

### 15.2 分阶段检查点

| 阶段 | 产出 | 检查点（不过则不进入下一阶段） |
| --- | --- | --- |
| P0 | 工程骨架 + 配置 | `mvnw package` 通过；目录与包名一致；yml 可解析 |
| P1 | 数据库 | 4 张表存在；3 商品 / 2 用户；`user` 表名带反引号 |
| P2 | 公共层 | `Result` 序列化为 `{code,msg,data}`；错误码数值正确 |
| P3 | 登录 + 商品 | 登录返回 token；商品 6 条；详情不存在返回 500101 |
| P4 | 秒杀主链路 | Redis 库存 100；首次成功；重复 500202；MQ 有消息 |
| P5 | 消费者 | DB 库存 -1；order_info + seckill_order 各 1 条；结果 Key 有 orderId |
| P6 | 轮询 | 先 -1 后 orderId；未登录 500102 |
| P7 | 限流 | 5 秒内第 6 次 500206 |
| P8 | 压测 | 订单 ≤ 100；无重复；脚本可生成报告 |

### 15.3 开始前只问三个问题

不要问一堆细节。只有以下三个问题会实质改变交付物；其余按本文档默认值执行。

1. **是否包含前端？**只复刻当前仓库（不含），还是按第 11 节补建 Vue 3（含）。
2. **复刻现状还是修复已知问题？**默认复刻现状；若修复，按第 14.2 节优先级逐项确认。
3. **运行环境版本是否可调整？**默认 JDK 21 / Boot 4.1.0 / MySQL 8 / Redis / RabbitMQ；若目标机器不满足，先确认替代方案。

### 15.4 常见“AI 跑偏”与纠正

| 跑偏行为 | 为什么错 | 纠正指令 |
| --- | --- | --- |
| 用 Redisson / 分布式锁替代 Lua | Key 与原子语义改变，无法一比一 | “回到第 7.3 节 Lua，逐字使用” |
| 秒杀接口同步写 DB | 失去削峰与排队语义 | “只允许 Redis + MQ，落库交给消费者” |
| 把业务错误改成 HTTP 4xx/5xx | 前端与压测脚本按 200 + code 判断 | “GlobalExceptionHandler 返回 Result，HTTP 200” |
| 给商品列表加 Redis 缓存 | 文档明确未实现 | “删除缓存，直接 selectList” |
| 把 Key 写成 user:goods 顺序 | 读取/写入会互相找不到 | “统一 {goodsId}:{userId}” |
| 给消费者加 @Transactional | 改变失败/重试行为 | “先复刻现状，修复需确认” |
| 自动加 CORS | 超出范围，且与“一比一”冲突 | “移除 CORS，前端用代理” |
| 跳过 JMeter | 无法验收不超卖/防重 | “必须交付 jmeter/ 并跑验收 SQL” |

### 15.5 最终交付报告模板

<!-- acceptance report template -->
```text
FlashSale 复刻验收报告
────────────────────────────
环境：JDK ___ / Boot ___ / MySQL ___ / Redis ___ / RabbitMQ ___
时间：____-__-__

一、构建
[ ] mvnw package 通过
[ ] 应用启动成功，库存预热日志出现

二、接口
[ ] 登录成功 / 失败码正确
[ ] 商品列表 6 条 / 详情正确
[ ] 首次秒杀成功
[ ] 重复秒杀 500202
[ ] 轮询 -1 → orderId
[ ] 未登录 500102
[ ] 限流 500206

三、数据一致性
[ ] Redis 库存：____
[ ] order_info 条数：____
[ ] seckill_order 条数：____
[ ] 不超卖校验：OK / FAIL
[ ] 不重复校验：0 / __

四、压测
[ ] JMeter 脚本可运行
[ ] 样本数 / 平均响应 / 吞吐：____
[ ] 报告路径：____

五、差异
[ ] 第 14 节差异均已确认
[ ] 未静默修改行为

结论：PASS / FAIL
遗留问题：____________________
```

### 15.6 最后一句

> **复刻的本质**
>
> 不是把代码抄一遍，而是把**约束**抄一遍：哪些 Key、哪些返回码、哪些失败分支、哪些“故意不修”的问题。约束一致，行为才一致。

本说明书由仓库当前快照生成：`FlashSaleBackend/src/main/java`、`pom.xml`、`application.yml`、`schema.sql`、`docker-compose.yml`、`jmeter/`、`README.md`、`OUTLINE.md`、`GUIDE.md`。

文档版本 v1.1 · 生成日期 2026-10-31 · 修订：包名 com.ftllight.flashsalebackend、种子密码哈希按实际 MD5 结果校正 · 适用：开发者 / AI Coding Agent · 与代码冲突时以第 14 节“代码事实”为准。

> 本文档由 `docs/FlashSale-开发说明书.html` 转换生成，内容一致。
