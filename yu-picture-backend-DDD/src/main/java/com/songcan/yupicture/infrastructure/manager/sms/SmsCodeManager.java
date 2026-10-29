package com.songcan.yupicture.infrastructure.manager.sms;

import cn.hutool.core.util.StrUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码的存取与发送频率限制
 */
@Component
public class SmsCodeManager {

    /**
     * 验证码
     */
    private static final String CODE_KEY = "sms:code:";

    /**
     * 发送间隔限制
     */
    private static final String LIMIT_KEY = "sms:limit:";

    /**
     * 每日发送次数
     */
    private static final String DAILY_KEY = "sms:daily:";

    /**
     * 验证失败次数
     */
    private static final String ATTEMPT_KEY = "sms:attempt:";

    /**
     * 验证码最多允许输错的次数
     */
    private static final int MAX_ATTEMPT = 5;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取一次发送额度
     *
     * @param phone           手机号
     * @param intervalSeconds 发送间隔（秒）
     * @param dailyLimit      每日上限
     * @return 是否允许发送
     */
    public boolean tryAcquireSendQuota(String phone, int intervalSeconds, int dailyLimit) {
        String dailyKey = DAILY_KEY + phone;
        String usedStr = stringRedisTemplate.opsForValue().get(dailyKey);
        int used = StrUtil.isBlank(usedStr) ? 0 : Integer.parseInt(usedStr);
        if (used >= dailyLimit) {
            return false;
        }
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(LIMIT_KEY + phone, "1", intervalSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        Long count = stringRedisTemplate.opsForValue().increment(dailyKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(dailyKey, 1, TimeUnit.DAYS);
        }
        return true;
    }

    /**
     * 保存验证码
     *
     * @param phone           手机号
     * @param code            验证码
     * @param expireMinutes   有效期（分钟）
     */
    public void saveCode(String phone, String code, int expireMinutes) {
        stringRedisTemplate.opsForValue().set(CODE_KEY + phone, code, expireMinutes, TimeUnit.MINUTES);
    }

    /**
     * 校验验证码，成功后立即失效
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 是否正确
     */
    public boolean verifyCode(String phone, String code) {
        String codeKey = CODE_KEY + phone;
        String savedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (StrUtil.isBlank(savedCode)) {
            return false;
        }
        if (savedCode.equals(code)) {
            stringRedisTemplate.delete(Arrays.asList(codeKey, ATTEMPT_KEY + phone));
            return true;
        }
        Long attempts = stringRedisTemplate.opsForValue().increment(ATTEMPT_KEY + phone);
        if (attempts != null && attempts == 1L) {
            stringRedisTemplate.expire(ATTEMPT_KEY + phone, 5, TimeUnit.MINUTES);
        }
        if (attempts != null && attempts >= MAX_ATTEMPT) {
            stringRedisTemplate.delete(Arrays.asList(codeKey, ATTEMPT_KEY + phone));
        }
        return false;
    }

    /**
     * 删除验证码（发送失败时回滚）
     *
     * @param phone 手机号
     */
    public void deleteCode(String phone) {
        stringRedisTemplate.delete(CODE_KEY + phone);
    }
}
