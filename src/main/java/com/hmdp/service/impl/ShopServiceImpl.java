package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result getShopById(Long id)  {
        /*缓存穿透
        Shop shop = queryWithPassThrough(id);
        return Result.ok(shop);*/
        /*缓存击穿
        Shop shop=queryWithMutex(id);*/
        /*缓存击穿--逻辑过期
        Shop shop=queryWithLogicalExpire(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);*/
        //工具类缓存穿透
        Shop shop=cacheClient.queryWithPassThrough(id,RedisConstants.CACHE_SHOP_KEY,Shop.class,this::getById,20L,TimeUnit.MINUTES );
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);




    }

    boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    boolean unlock(String key){
        return stringRedisTemplate.delete(key);

    }
    /**
     * 基于逻辑过期时间解决缓存击穿问题
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private Shop queryWithLogicalExpire(Long id){
            String lockkey=RedisConstants.LOCK_SHOP_KEY+id;

            String shopRedisData = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if(StrUtil.isBlank(shopRedisData)){
                return null;
            }
            RedisData redisData = JSONUtil.toBean(shopRedisData, RedisData.class);
            LocalDateTime expireTime = redisData.getExpireTime();
            Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
            //如果没有过期则直接返回
            if(expireTime.isAfter(LocalDateTime.now())) {
                return shop;
            }
            //如果过期了，尝试获取锁
            boolean lock = tryLock(lockkey);
            //锁未被占用，则开启一个新线程访问数据库
            if(lock){

                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        //为了保证代码有异常也能unlock,需要try catch final
                        try {
                            rebuildCache(id,20);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            unlock(lockkey);
                        }
                    });

            }


        //不论锁有没有被占用，都直接返回旧的数据，if(lock)里面只是开了一个新的线程，具体的执行由子线程执行，主线程还是继续返回旧数据

            return shop;





    }

    private void rebuildCache(Long id, int expireSeconds) throws InterruptedException {
        Thread.sleep(200);
        Shop shop = getById(id);
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(expireSeconds),shop);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }


    /**
     * 缓存击穿
     */
    private Shop queryWithMutex(Long id){
        String lockkey = RedisConstants.LOCK_SHOP_KEY+id;

        Shop shop = null;
        try {
            String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            //如果店铺存在则返回
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            //如果shopJson=""也就是空值，缓存穿透的结果，也直接返回
            if (shopJson != null) {
                return null;

            }

            //如果缓存里店铺不存在，尝试锁


            boolean lock = tryLock(lockkey);
            //如果锁被占用，休眠后递归
            if (!lock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            //根据id从数据库中取数据
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockkey);
        }
        return shop;


    }




    /**
     * 缓存穿透
     */
    private Shop queryWithPassThrough(Long id) {
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断是否为空值，shopJson的几种可能："",具体店铺信息,null
        if(shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //根据id查询数据库
        Shop shop = getById(id);

        //不存在，返回错误信息，同时将空值写入redis,防止缓存穿透
        if(shop == null){
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在则将店铺信息写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        //万一删除失败，则进行事务回滚
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }


}
