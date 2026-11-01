package com.songcan.yupicture.interfaces.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 通过手机验证码重置/设置密码请求
 */
@Data
public class UserPasswordResetRequest implements Serializable {

    /**
     * 手机号
     */
    private String phone;

    /**
     * 短信验证码
     */
    private String code;

    /**
     * 新密码
     */
    private String newPassword;

    /**
     * 确认密码
     */
    private String checkPassword;

    private static final long serialVersionUID = 1L;
}
