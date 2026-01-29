package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIME_STAMP=1640995200L;
    private int COUNT_BITS=32;

    public long nextId(String keyPrefix){
        //1.计算时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds= now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSeconds - BEGIN_TIME_STAMP;
        //2.生成序列号
        LocalDate today = LocalDate.now();
        String date = today.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date );
        return timestamp<<COUNT_BITS | count;

    }



}
