-- Claim a pending message before its database transaction.
-- Return: 1 claimed, 2 already claimed/successful, 0 no live reservation.

local pending = redis.call('GET', KEYS[1])
if not pending then
    return 0
end

local result = redis.call('GET', KEYS[2])
if result == '1' then
    return 2
end
if result ~= '0' then
    return 0
end
if string.find(pending, '^PROCESSING|') then
    return 2
end

-- PENDING|user|quantity|createdAt|messageId
redis.call('SET', KEYS[1], 'PROCESSING|' .. string.sub(pending, 9) .. '|' .. ARGV[1])
return 1
