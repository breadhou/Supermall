-- =============================================
-- KEYS[1]: 商品库存的 Key (例如: "sec_kill:stock:1001")
-- ARGV[1]: 本次要扣减的数量 (例如: 1)
-- =============================================

local stock_key = KEYS[1]
local deduct_num = tonumber(ARGV[1])

-- 1. 安全校验：扣减数量必须大于 0
if not deduct_num or deduct_num <= 0 then
    return -2 -- 返回 -2 代表入参不合法
end

-- 2. 检查 Redis 中是否存在该库存 Key
local current_stock = redis.call("get", stock_key)
if not current_stock then
    return -1 -- 返回 -1 代表库存未初始化或商品不存在
end

current_stock = tonumber(current_stock)

-- 3. 核心判断：当前库存是否足够扣减（防超卖）
if current_stock < deduct_num then
    return 0 -- 返回 0 代表库存不足（已售罄）
end

-- 4. 执行原子扣减操作
redis.call("decrby", stock_key, deduct_num)

return 1 -- 返回 1 代表扣减成功！