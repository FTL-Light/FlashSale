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
