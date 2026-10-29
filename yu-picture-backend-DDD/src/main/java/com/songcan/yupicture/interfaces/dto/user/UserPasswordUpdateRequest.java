package com.songcan.yupicture.interfaces.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 设置/修改密码请求
 */
@Data
public class UserPasswordUpdateRequest implements Serializable {

    /**
     * 原密码（未设置过密码时可不传）
     */
    private String oldPassword;

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
