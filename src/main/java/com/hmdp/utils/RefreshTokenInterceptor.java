package com.hmdp.utils;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (StringUtils.isBlank(token)) {
            return true;
        }
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        if (entries.isEmpty() ) {
            return true;
        }

                UserDTO userDTO = new UserDTO();
                Object id = entries.get("id");
                Object nickName = entries.get("nickName");
                Object icon = entries.get("icon");
                userDTO.setId(Long.valueOf(id.toString()));
                userDTO.setNickName(String.valueOf(nickName));
                userDTO.setIcon(String.valueOf(icon));

                //有用户信息，则回写到ThreadLocal中，以便后续用户的使用

                UserHolder.saveUser(userDTO);
                stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
                return true;

    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();

    }


}
