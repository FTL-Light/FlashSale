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
