# CLAUDE.md

给 AI 编码代理 / 协作者的环境提示，与业务无关。业务契约见 [docs/FlashSale-开发说明书.md](docs/FlashSale-开发说明书.md)。

## 网络与代理

- 本机直连 `github.com` 可能被重置，表现为 `Recv failure: Connection was reset`。此时给 Git 配代理：

```powershell
git config http.proxy http://127.0.0.1:7897   # 端口按自己的客户端改
# 取消：git config --unset http.proxy
```

- Maven / npm 同理，可用 `HTTPS_PROXY` 环境变量；Maven 还可在 `~/.m2/settings.xml` 的 `<proxies>` 中配置。

## 构建

- 文档要求 JDK 21 + Maven 3.9.x（`FlashSaleBackend/mvnw` 自带 wrapper，首次运行会下载 Maven 3.9.9）。
- 本机只有 JDK 17 时，可临时加 `-Dmaven.compiler.release=17` 做静态编译检查；**不要因此改 pom**。
- 若 `mvn` 报 `Could not create local repository at D:\...`，说明全局 `settings.xml` 的 `localRepository` 指向了不存在的盘符。用 `mvn -s <临时 settings>` 覆盖，不要改全局配置。
- PowerShell 5 下把 `-D` 参数拆坏的常见原因：参数顺序与引号。稳妥写法 `mvn -s $cfg -B -DskipTests "-Dmaven.compiler.release=17" compile`。

## 运行依赖

- 必须外部提供 MySQL 8、Redis、RabbitMQ；`docker-compose.yml` 只起 RabbitMQ。
- `application.yml` 中 `spring.datasource.password` 是 `${MYSQL_PASSWORD}`，无默认值，未设置会直接启动失败。
- 启动即预热 Redis 库存；Redis 数据丢失时重启应用即可恢复（以 DB 库存为准）。

## 提交约定

- 不要提交 `.env`、真实密码、`node_modules/`、`target/`、`dist/`、`jmeter/results/`。
- 行为以 `docs/FlashSale-开发说明书.md` 为准，不要擅自“优化”第 14 节列出的已知行为（无 `@Transactional`、唯一键冲突重试、30 分钟 TTL 等）。
