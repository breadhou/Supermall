-- Atomic seckill reservation.
-- KEYS[1] path, KEYS[2] result, KEYS[3] user limit,
-- KEYS[4] stock, KEYS[5] pending message, KEYS[6] pending index.
-- ARGV[1] expected path, [2] user id, [3] quantity, [4] user limit,
-- ARGV[5] message id, [6] creation time, [7] result TTL seconds.
-- Return: 1 reserved, 0 stock empty, -1 stock not preheated,
--         -2 invalid arguments, -3 invalid path, -4 repeated request.

local expected_path = ARGV[1]
local user_id = ARGV[2]
local quantity = tonumber(ARGV[3])
local limit_per_user = tonumber(ARGV[4])
local message_id = ARGV[5]
local created_at = ARGV[6]
local result_ttl = tonumber(ARGV[7])

if not expected_path or expected_path == ''
        or not quantity or quantity <= 0
        or not limit_per_user or limit_per_user <= 0
        or not message_id or message_id == '' then
    return -2
end

if redis.call('GET', KEYS[1]) ~= expected_path then
    return -3
end

local result = redis.call('GET', KEYS[2])
if result == '0' or result == '1' or redis.call('EXISTS', KEYS[5]) == 1 then
    return -4
end

local current_limit = tonumber(redis.call('GET', KEYS[3]) or '0')
if current_limit + quantity > limit_per_user then
    return -4
end

local current_stock = tonumber(redis.call('GET', KEYS[4]) or '')
if not current_stock then
    return -1
end
if current_stock < quantity then
    return 0
end

redis.call('DECRBY', KEYS[4], quantity)
redis.call('INCRBY', KEYS[3], quantity)
redis.call('SET', KEYS[2], '0', 'EX', result_ttl)
redis.call('SET', KEYS[5], 'PENDING|' .. user_id .. '|' .. quantity .. '|' .. created_at .. '|' .. message_id)
redis.call('ZADD', KEYS[6], tonumber(created_at), message_id)

return 1
