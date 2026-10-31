# FlashSale JMeter 压测指南

JMeter 在本项目里是验收工具，用于验证 **不超卖** 与 **不重复**。

## 1. 前置条件

- JDK 21、MySQL 8、Redis、RabbitMQ 已启动，后端已运行在 `localhost:8080`
- JMeter 5.6.3，设置环境变量 `JMETER_HOME` 指向安装目录（如 `D:\jmeter\apache-jmeter-5.6.3`），未设置时脚本回退到该默认路径
- `mysql`、`redis-cli` 在 PATH 中
- 已设置环境变量 `MYSQL_PASSWORD`

## 2. 数据准备

```powershell
$env:MYSQL_PASSWORD = "你的密码"
mysql -uroot -p -D flashsale < jmeter\prepare.sql
```

`prepare.sql` 会清理用户 2 之后的测试数据，并生成 500 个测试用户（手机号 `13800000003` 起）。
`users.csv` 默认只有 2 个种子用户；真实压测前请扩充为用户池，或把 CSV 指向 `prepare.sql` 生成的账号（密码均为 `123456`）。

重置库存与 Redis：

```powershell
redis-cli --scan --pattern "seckill:*" | ForEach-Object { redis-cli DEL $_ }
redis-cli --scan --pattern "ratelimit:seckill:*" | ForEach-Object { redis-cli DEL $_ }
mysql -uroot -p -e "DROP DATABASE IF EXISTS flashsale;"
mysql -uroot -p < FlashSaleBackend\src\main\resources\schema.sql
```

重启后端以触发 `@PostConstruct` 库存预热。

## 3. 运行

```powershell
.\run-test.ps1 -Mode light     # 50 线程 / 5s ramp-up / 30s
.\run-test.ps1 -Mode medium    # 200 线程 / 10s / 60s
.\run-test.ps1 -Mode heavy     # 500 线程 / 10s / 120s
.\run-test.ps1 -Mode custom -Threads 300 -RampUp 10 -Duration 90
```

也可以直接双击 `run-test.bat`（等价于 medium 档位）。

脚本会：

1. 读取 `flashsale-load-test.jmx`，替换 `@@THREADS@@` / `@@RAMPUP@@` / `@@DURATION@@` / `@@SETUP_THREADS@@` / `@@SETUP_RAMPUP@@`
2. 把 CSV 改为绝对路径，输出到 `results/flashsale-temp.jmx`
3. 调用 JMeter 非 GUI 模式，生成 `results/*.jtl` 与 `results/report-*/`

`@@SETUP_THREADS@@` 取 `min(THREADS, 500)`，登录线程 ramp-up 固定 5 秒。

## 4. 报告

- `results/flashsale-*.jtl`：原始样本
- `results/report-*/index.html`：HTML 报告

`simulate.ps1` 会生成 8500 条 **模拟** 样本用于演示报告结构，**不是真实压测结论**，不要引用其性能数字。

## 5. 数据校验

```sql
-- 1) 不超卖：库存与订单数对比
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

通过标准：

- 商品 1 初始库存 100，`order_info` 中 `goods_id=1` 的订单数 ≤ 100
- 重复校验返回 0
- `Redis 剩余库存 + 成功订单数 = 初始库存`（同一轮运行内）
