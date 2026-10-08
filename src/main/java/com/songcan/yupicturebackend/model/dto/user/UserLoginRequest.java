package com.songcan.yupicturebackend.model.dto.user;

import lombok.Data;

import java.io.Serializable;


/**
 * 用户登录
 */
@Data
public class UserLoginRequest implements Serializable {

    /**
     * 账号
     */
    private String UserAccount;

    /**
     * 密码
     */
    private String UserPassword;

}
