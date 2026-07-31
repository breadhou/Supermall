-- Finalize a committed order.
-- Return: 1 finalized, 2 already finalized, 0 reservation no longer live.

local result = redis.call('GET', KEYS[1])
if result == '1' then
    return 2
end
if result ~= '0' or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end

redis.call('SET', KEYS[1], '1', 'EX', tonumber(ARGV[2]))
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[3], ARGV[1])
return 1
