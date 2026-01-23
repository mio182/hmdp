package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
