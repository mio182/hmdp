package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //方法1：将任意java对象序列化为json并存储在string类型的key中，并且可以设置ttl过期时间
    public void setJsonWithTTL(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,timeUnit);


    }
    //方法2：将任意java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setJsonWithLogicExpireTime(String key,Object value,Long time,TimeUnit timeUnit){
        LocalDateTime expireTime=LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time));
        RedisData redisData = new RedisData(expireTime, value);
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));



    }

    //方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    //id不一定是Long所以也给个泛型
    /**
     * 缓存穿透
     */
    public <R,ID> R queryWithPassThrough(ID id,String keyPrefix,Class<R> type,Function<ID,R> fallbackdb,Long time,TimeUnit timeUnit) {
        //1.从redis查询商铺缓存
        String key=keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //判断是否为空值，shopJson的几种可能："",具体店铺信息,null
        if(json!=null){
            //返回一个错误信息
            return null;
        }

        //根据id查询数据库
        R r = fallbackdb.apply(id);

        //不存在，返回错误信息，同时将空值写入redis,防止缓存穿透
        if(r == null){
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;

        }
        //存在则将店铺信息写入redis
        setJsonWithLogicExpireTime(key,r,time,timeUnit);

        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    //方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    private <R,ID> R queryWithLogicalExpire(ID id,String keyPrefix,String lockPrefix,Class<R>type,Function<ID,R>dbFallback,TimeUnit timeUnit,Long time) {
        String lockkey=lockPrefix+id;
        String key=keyPrefix + id;
        String thisRedisData = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(thisRedisData)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(thisRedisData, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        //如果没有过期则直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //如果过期了，尝试获取锁
        boolean lock = tryLock(lockkey) ;
        //锁未被占用，则开启一个新线程访问数据库
        if(lock){

            CACHE_REBUILD_EXECUTOR.submit(()->{
                //为了保证代码有异常也能unlock,需要try catch final
                try {
                    R r1 = dbFallback.apply(id);
                    setJsonWithLogicExpireTime(key,r1,time,timeUnit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockkey);
                }
            });

        }


        //不论锁有没有被占用，都直接返回旧的数据，if(lock)里面只是开了一个新的线程，具体的执行由子线程执行，主线程还是继续返回旧数据

        return r;
    }


    boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    boolean unlock(String key){
        return stringRedisTemplate.delete(key);

    }



}
