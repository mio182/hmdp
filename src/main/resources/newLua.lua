---
--- Created by akena.
--- DateTime: 2026-02-02 11:16
---
if(redis.call('GET',KEYS[1])==ARGV[1]) then
    return redis.call('DEL',KEYS[1])
end
return 0
