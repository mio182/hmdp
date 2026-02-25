package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;





/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill2.lua"));
    }



    private ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new SeckillHandler());
    }


    private class SeckillHandler implements Runnable {
        String queueName="stream.orders";

        @Override
        public void run() {
            while(true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())

                    );

                    //2.判断消息获取是否成功
                    if(list==null||list.isEmpty()){
                        //2.1.如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }

        }

        private void handlePendingList() {
            while(true){
                try {
                    //1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))

                    );

                    //2.判断消息获取是否成功
                    if(list==null||list.isEmpty()){
                        //2.1.如果获取失败，说明pending-list没有异常，结束循环
                        break;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }


        }
    }

   /*
    //阻塞队列
   private BlockingQueue<VoucherOrder> queue = new ArrayBlockingQueue<>(1024*1024);
   private class SeckillHandler implements Runnable {

        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = queue.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }

        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //因为这是新开的线程，无法从UserHolder中获取用户信息
        String userId = voucherOrder.getId().toString();
        //获取锁

        RLock iLock = redissonClient.getLock("lock:order:" + userId);
        boolean lock = iLock.tryLock();
        //判断是否获取锁成功
        if (!lock) {
            log.error("获取锁失败");
            return ;
        }


        try {
             proxy.createVoucherOrder(voucherOrder);
        } finally {
            //不论是否出现异常，都释放锁
            iLock.unlock();
        }



    }



    private  IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long id = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");

        //执行lua脚本,脚本的功能是：判断购买资格，发送消息到阻塞队列
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), id.toString(),String.valueOf(orderId));



        //返回1或者2，则失败
        int r= result.intValue();

        if(r!=0){
            return r==1?Result.fail("库存不足"):Result.fail("用户下过单");
        }



        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();



        return Result.ok(orderId);
    }

   /* private  IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long id = UserHolder.getUser().getId();

        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), id.toString());



        //返回1或者2，则失败
        int r= result.intValue();

        if(r!=0){
            return r==1?Result.fail("库存不足"):Result.fail("用户下过单");
        }

        //返回0则将订单信息存入阻塞队列


        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(id);
        queue.add(voucherOrder);

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();



        return Result.ok(orderId);











    }
*/



    /*@Override
    //因为设计两张表的操作，所以最好加上事务注解，一旦出现问题可以回滚
    public Result seckillVoucher(Long voucherId) {
        //查看是否在有效期内
        LocalDateTime now = LocalDateTime.now();
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("没有该id对应的优惠券");
        }
        if (now.isBefore(seckillVoucher.getBeginTime()) || now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀券已过期");
        }
        //查看是否有库存，无库存则返回失败信息
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("秒杀券没有库存");
        }
        String userId = UserHolder.getUser().getId().toString();
        //获取锁
        //ILock iLock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        RLock iLock = redissonClient.getLock("lock:order:" + userId);
        boolean lock = iLock.tryLock();
        //判断是否获取锁成功
        if (!lock) {
            return Result.fail("一个人只允许下一单");
        }

        //获取代理对象（事务）
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //不论是否出现异常，都释放锁
            iLock.unlock();
        }


    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单问题
        //查询是否有该用户id下的优惠券id为voucherId的订单，如果有则返回失败信息
        Long userId = voucherOrder.getUserId();


        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过该优惠券");
            return ;
        }

        //库存减一


        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }


        save(voucherOrder);



    }



   /* @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单问题
        //查询是否有该用户id下的优惠券id为voucherId的订单，如果有则返回失败信息
        Long userId = UserHolder.getUser().getId();


        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("该用户已购买过该优惠券");
        }

        //库存减一


        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        //下订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        long id = redisIdWorker.nextId("order");
        voucherOrder.setId(id);
        save(voucherOrder);

        return Result.ok(id);

    }*/
}
