package com.songcan.yupicture.infrastructure.api.sms;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.songcan.yupicture.infrastructure.config.AliyunSmsConfig;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阿里云号码认证服务（SendSmsVerifyCode 接口，RPC 签名）
 */
@Slf4j
@Component
public class AliyunSmsApi {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Resource
    private AliyunSmsConfig aliyunSmsConfig;

    /**
     * 发送验证码短信
     *
     * @param phone 手机号
     * @param code  验证码
     */
    public void sendVerificationCode(String phone, String code) {
        if (aliyunSmsConfig.isMock()) {
            log.info("短信 mock 模式，手机号 {} 的验证码为：{}", phone, code);
            return;
        }
        String accessKeyId = aliyunSmsConfig.getAccessKeyId();
        String accessKeySecret = aliyunSmsConfig.getAccessKeySecret();
        if (StrUtil.hasBlank(accessKeyId, accessKeySecret, aliyunSmsConfig.getSignName(),
                aliyunSmsConfig.getTemplateCode())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "短信服务未配置，请检查 application-local.yml");
        }

        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", "SendSmsVerifyCode");
        params.put("Format", "JSON");
        params.put("PhoneNumber", phone);
        params.put("SignName", aliyunSmsConfig.getSignName());
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", IdUtil.fastSimpleUUID());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", aliyunSmsConfig.getTemplateCode());
        params.put("TemplateParam", JSONUtil.createObj()
                .set(aliyunSmsConfig.getTemplateParamName(), code)
                .set(aliyunSmsConfig.getTemplateMinuteParamName(),
                        String.valueOf(aliyunSmsConfig.getCodeExpireMinutes()))
                .toString());
        // ValidTime 单位是秒
        params.put("ValidTime", String.valueOf(aliyunSmsConfig.getCodeExpireMinutes() * 60));
        params.put("DuplicatePolicy", "1");
        params.put("Interval", String.valueOf(aliyunSmsConfig.getResendIntervalSeconds()));
        params.put("Timestamp", TIMESTAMP_FORMATTER.format(LocalDateTime.now(ZoneOffset.UTC)));
        params.put("Version", "2017-05-25");

        String signature = sign(params, accessKeySecret);
        StringBuilder url = new StringBuilder(aliyunSmsConfig.getEndpoint())
                .append("/?Signature=").append(percentEncode(signature));
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append("&").append(percentEncode(entry.getKey()))
                    .append("=").append(percentEncode(entry.getValue()));
        }

        JSONObject result;
        String body;
        try (HttpResponse response = HttpRequest.get(url.toString())
                .timeout(10000)
                .execute()) {
            body = response.body();
            result = JSONUtil.parseObj(body);
        } catch (Exception e) {
            log.error("调用阿里云短信接口失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "短信发送失败，请稍后重试");
        }
        if (!"OK".equals(result.getStr("Code"))) {
            log.error("阿里云短信发送失败：code={}, message={}, body={}",
                    result.getStr("Code"), result.getStr("Message"), body);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "短信发送失败：" + StrUtil.blankToDefault(result.getStr("Message"), "请稍后重试"));
        }
    }

    /**
     * 计算阿里云 RPC 签名
     *
     * @param params          请求参数
     * @param accessKeySecret AccessKey Secret
     * @return 签名
     */
    private String sign(Map<String, String> params, String accessKeySecret) {
        StringBuilder canonicalizedQueryString = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (canonicalizedQueryString.length() > 0) {
                canonicalizedQueryString.append("&");
            }
            canonicalizedQueryString.append(percentEncode(entry.getKey()))
                    .append("=")
                    .append(percentEncode(entry.getValue()));
        }
        String stringToSign = "GET&" + percentEncode("/") + "&"
                + percentEncode(canonicalizedQueryString.toString());
        // 用 JDK 自带的 HmacSHA1，不要用 Hutool 的 HMac：
        // COS SDK 带进来的 bcprov 是签名 jar，打进 Spring Boot 嵌套 jar 后
        // JCE 无法验证签名（JCE cannot authenticate the provider BC），
        // 而 Hutool 会优先选择 BouncyCastle，导致线上发短信必失败。
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8),
                    "HmacSHA1"));
            return Base64.encode(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信签名计算失败");
        }
    }

    /**
     * 阿里云要求的 RFC 3986 编码
     *
     * @param value 原始值
     * @return 编码后的值
     */
    private String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信签名编码失败");
        }
    }
}
