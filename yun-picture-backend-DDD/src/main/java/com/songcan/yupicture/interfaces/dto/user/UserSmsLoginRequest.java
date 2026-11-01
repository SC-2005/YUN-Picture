package com.songcan.yupicture.interfaces.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 短信验证码登录请求
 */
@Data
public class UserSmsLoginRequest implements Serializable {

    /**
     * 手机号
     */
    private String phone;

    /**
     * 验证码
     */
    private String code;

    private static final long serialVersionUID = 1L;
}
