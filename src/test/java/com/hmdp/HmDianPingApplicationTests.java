package com.hmdp;

import cn.hutool.cron.task.RunnableTask;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    ExecutorService executorService= Executors.newFixedThreadPool(500);

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


    @Test
    void testRedisIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task=()->{
            for (int i=0;i<100;i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            //每完成一个任务，就减1，直到减完300，说明所有线程都执行完，于是await也会结束
            countDownLatch.countDown();
        };
        long start = System.currentTimeMillis();

        for(int i=0;i<300;i++) {
            executorService.submit(task);
        }
        //主线程布置完任务就开始等待
        countDownLatch.await();

        long end=System.currentTimeMillis();
        System.out.println(end-start);
    }



    }


