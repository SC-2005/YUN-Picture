package com.songcan.yupicture.interfaces.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 发送短信验证码请求
 */
@Data
public class UserSmsSendRequest implements Serializable {

    /**
     * 手机号
     */
    private String phone;

    private static final long serialVersionUID = 1L;
}
