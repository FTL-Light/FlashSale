# 界面截图

存放运行截图，用于 README 展示。

| 文件 | 页面 |
| --- | --- |
| `goods-list.png` | 商品列表（`/goods`） |
| `seckill-result.png` | 秒杀结果（`/order/1`） |

拍摄步骤：

1. 启动 MySQL、Redis、RabbitMQ，导入 `FlashSaleBackend/src/main/resources/schema.sql`
2. 启动后端与前端，浏览器访问 `http://localhost:5173/goods`，用 `13800000001 / 123456` 登录
3. `Win + Shift + S` 截图，按上表文件名保存到本目录
4. 取消 [README.md](../../README.md) 中「界面预览」小节里被注释的图片引用
