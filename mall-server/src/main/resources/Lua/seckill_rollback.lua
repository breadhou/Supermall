-- Roll back a reservation.  KEYS share the item hash tag.
-- ARGV[1] message id, [2] user id, [3] quantity, [4] allow processing,
-- [5] result TTL seconds.
-- Return: 1 rolled back, 2 already completed/no-op, 3 in processing,
--         0 missing reservation, -1 stock key unavailable.

local result = redis.call('GET', KEYS[2])
if result == '1' then
    return 2
end

local pending = redis.call('GET', KEYS[4])
if not pending then
    return 0
end
if string.find(pending, '^PROCESSING|') and ARGV[4] ~= '1' then
    return 3
end

local stock = tonumber(redis.call('GET', KEYS[1]) or '')
if not stock then
    return -1
end

redis.call('INCRBY', KEYS[1], tonumber(ARGV[3]))
local limit = tonumber(redis.call('GET', KEYS[3]) or '0') - tonumber(ARGV[3])
if limit > 0 then
    redis.call('SET', KEYS[3], limit)
else
    redis.call('DEL', KEYS[3])
end
redis.call('SET', KEYS[2], '-1', 'EX', tonumber(ARGV[5]))
redis.call('DEL', KEYS[4])
redis.call('ZREM', KEYS[5], ARGV[1])
return 1
