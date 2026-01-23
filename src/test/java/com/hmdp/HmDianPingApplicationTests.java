package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void initRedis(){
        /*List<Shop> list = shopService.list();
        for(Shop shop:list){
            RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(10), shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+shop.getId(), JSONUtil.toJsonStr(redisData));*/
        Object object="";
        Class<?> aClass = object.getClass();
        String name = aClass.getSimpleName();
        System.out.println(name);


    }

    }


