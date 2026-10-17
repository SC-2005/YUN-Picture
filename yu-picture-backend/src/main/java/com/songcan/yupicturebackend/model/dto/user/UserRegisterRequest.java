package com.songcan.yupicturebackend.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户注册
 */
@Data
public class UserRegisterRequest implements Serializable {

    /**
     * 序列化id
     */
    private static final long serialVersionUID = -3056725109465203391L;

    /**
     * 账号
     */
    private String UserAccount;

    /**
     * 密码
     */
    private String UserPassword;

    /**
     * 确认密码
     */
    private String CheckPassword;

}
