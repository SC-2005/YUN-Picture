package com.songcan.yupicture.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云号码认证服务（短信验证码）配置（值来自 application-local.yml 中 aliyun.sms.* 配置，不要提交到仓库）
 */
@Configuration
@ConfigurationProperties(prefix = "aliyun.sms")
@Data
public class AliyunSmsConfig {

    /**
     * AccessKey ID
     */
    private String accessKeyId;

    /**
     * AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * 短信签名
     */
    private String signName;

    /**
     * 短信模板 Code
     */
    private String templateCode;

    /**
     * 模板中验证码参数名，默认 code
     */
    private String templateParamName = "code";

    /**
     * 模板中有效期参数名，默认 min（单位分钟）
     */
    private String templateMinuteParamName = "min";

    /**
     * 接口地址
     */
    private String endpoint = "https://dypnsapi.aliyuncs.com";

    /**
     * 验证码有效期（分钟）
     */
    private int codeExpireMinutes = 5;

    /**
     * 同一手机号两次发送的最小间隔（秒）
     */
    private int resendIntervalSeconds = 60;

    /**
     * 同一手机号每天最多发送次数
     */
    private int dailyLimit = 10;

    /**
     * 本地联调模式：不真正调用阿里云，只把验证码打印到日志
     */
    private boolean mock = false;
}
