package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;



    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.检验手机号是否合规
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不合规");
        }
        //2.生成验证码
        String code=RandomUtil.randomNumbers(6);
       /* 3.保存验证码到session
        session.setAttribute("code",code );*/
        //3.保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);


        //4.发送验证码
        log.debug("验证码发送成功，验证码{}",code);
        //log.debug("sendCode sessionId={}", session.getId());

       return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //0.校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号不合规");
        }
       /* 1.code是否和session中的code匹配
        String code = (String) session.getAttribute("code");
          2.1code是否合规
        if(code==null||RegexUtils.isCodeInvalid(loginForm.getCode())) {
            return Result.fail("验证码不合规");
        }*/
        //1.code是否和redis中的code匹配
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());


        //2.匹配失败则返回fail
        if (code==null|| !code.equals(loginForm.getCode())) {
            return Result.fail("验证码不匹配");
        }

        //3.匹配成功，查看数据库中是否有该用户的信息（手机号），在这里用到mybatisplus,不用自己写单条增删改查
        String phone =  loginForm.getPhone();
        User user=query().eq("phone",phone).one();
        //4.若没有，则创建用户
        if(user==null) {
            user=createUserWithPhone(phone);
        }
        UserDTO userDTO=new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
       /* 5.将用户信息放到session中
        session.setAttribute("user",userDTO);*/
        //5. 将用户的信息放到redis中

        String token=randomLetters();
        stringRedisTemplate.opsForHash().put(RedisConstants.LOGIN_USER_KEY+token,"id",userDTO.getId().toString());
        stringRedisTemplate.opsForHash().put(RedisConstants.LOGIN_USER_KEY+token,"nickName",userDTO.getNickName());
        stringRedisTemplate.opsForHash().put(RedisConstants.LOGIN_USER_KEY+token,"icon",userDTO.getIcon());


        //6.返回success
        //log.debug("login sessionId={}", session.getId());
        //7.设置有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result userSign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String month = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key="sign:"+userId+month;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
       //构造redis中的key
        //1.得到userId
        Long userId = UserHolder.getUser().getId();
        //2.得到今天所在月
        LocalDateTime now = LocalDateTime.now();
        String month = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
//        String key="sign:"+userId+month; 这是正常情况下的key,为了方便把key设置成了下面那个
        String key="test";

      //从redis中根据key获取本月签到的bit位
        //1.得到今天的日期，从而确定要获取几位比特位
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if(result==null||result.isEmpty()){
            return Result.ok(0);
        }
        //2.得到比特位
        Long num = result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        //进入循环
        int count=0;
        while(true){
            //1.和1与运算得到1，count++
            if((num&1)==0){
                break;
            }else{
                //2.和1与运算得到0，退出循环，返回
                count++;
            }
            num>>>=1;
        }
        return Result.ok(count);

    }


    private String randomLetters() {
        String letters="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            sb.append(letters.charAt(rng.nextInt(letters.length())));
        }
        return sb.toString();
    }


    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        return user;
    }
}
