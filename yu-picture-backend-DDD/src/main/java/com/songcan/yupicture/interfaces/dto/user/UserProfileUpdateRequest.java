package com.songcan.yupicture.interfaces.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 更新个人资料请求
 */
@Data
public class UserProfileUpdateRequest implements Serializable {

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    private static final long serialVersionUID = 1L;
}
