package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;


    @Override
    //因为设计两张表的操作，所以最好加上事务注解，一旦出现问题可以回滚
    public Result seckillVoucher(Long voucherId) {
        //查看是否在有效期内
        LocalDateTime now = LocalDateTime.now();
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("没有该id对应的优惠券");
        }
        if(now.isBefore(seckillVoucher.getBeginTime())|| now.isAfter(seckillVoucher.getEndTime())){
            return Result.fail("秒杀券已过期");
        }
        //查看是否有库存，无库存则返回失败信息
        if(seckillVoucher.getStock()<=0){
            return Result.fail("秒杀券没有库存");
        }
        Long userId = UserHolder.getUser().getId();

        synchronized (userId.toString().intern()) {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result  createVoucherOrder(Long voucherId) {
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

    }
}
