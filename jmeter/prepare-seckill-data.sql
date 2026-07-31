USE mall;

-- 独立压测数据：不要与 90000000000000000x 集成测试数据混用。
SET @category_id = 910000000000000001;
SET @product_id = 910000000000000002;
SET @sku_id = 910000000000000003;
SET @activity_id = 910000000000000004;
SET @item_id = 910000000000000006;

INSERT INTO category (id, name, parent_id, level, sort)
VALUES (@category_id, 'JMeter 压测分类', 0, 1, 999)
ON DUPLICATE KEY UPDATE name = VALUES(name), parent_id = VALUES(parent_id), level = VALUES(level), sort = VALUES(sort);

INSERT INTO product (id, name, description, category_id, merchant_id, status)
VALUES (@product_id, 'JMeter 秒杀压测商品', '仅用于本地压测', @category_id, 910000000000000005, 'ON_SHELF')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), category_id = VALUES(category_id), status = VALUES(status);

INSERT INTO product_sku (id, product_id, specs, price, stock, image)
VALUES (@sku_id, @product_id, '{"压测":"专用"}', 19.90, 1000000, NULL)
ON DUPLICATE KEY UPDATE product_id = VALUES(product_id), specs = VALUES(specs), price = VALUES(price), stock = VALUES(stock), image = VALUES(image);

INSERT INTO seckill_activity (id, name, start_time, end_time, status)
VALUES (@activity_id, 'JMeter 本地压测活动', NOW() - INTERVAL 1 MINUTE, NOW() + INTERVAL 2 HOUR, 'IN_PROGRESS')
ON DUPLICATE KEY UPDATE name = VALUES(name), start_time = VALUES(start_time), end_time = VALUES(end_time), status = VALUES(status);

INSERT INTO seckill_item (id, activity_id, sku_id, seckill_price, stock, limit_per_user)
VALUES (@item_id, @activity_id, @sku_id, 9.90, 1000000, 1)
ON DUPLICATE KEY UPDATE activity_id = VALUES(activity_id), sku_id = VALUES(sku_id), seckill_price = VALUES(seckill_price), stock = VALUES(stock), limit_per_user = VALUES(limit_per_user);

SELECT @item_id AS item_id, stock, limit_per_user
FROM seckill_item
WHERE id = @item_id;
