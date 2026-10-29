package com.songcan.yupicture.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicture.infrastructure.common.DeleteRequest;
import com.songcan.yupicture.interfaces.dto.user.UserLoginRequest;
import com.songcan.yupicture.interfaces.dto.user.UserPasswordResetRequest;
import com.songcan.yupicture.interfaces.dto.user.UserPasswordUpdateRequest;
import com.songcan.yupicture.interfaces.dto.user.UserProfileUpdateRequest;
import com.songcan.yupicture.interfaces.dto.user.UserQueryRequest;
import com.songcan.yupicture.interfaces.dto.user.UserSmsLoginRequest;
import com.songcan.yupicture.interfaces.dto.user.UserSmsSendRequest;
import com.songcan.yupicture.interfaces.dto.user.UserRegisterRequest;
import com.songcan.yupicture.domain.user.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.songcan.yupicture.interfaces.vo.user.LoginUserVO;
import com.songcan.yupicture.interfaces.vo.user.UserVO;

import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;

/**
 * @author 34451
 * @description 针对表【user(用户)】的数据库操作Service
 * @createDate 2026-09-03 13:22:03
 */
public interface UserApplicationService{

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    long userRegister(UserRegisterRequest userRegisterRequest);

    /**
     * 用户登录
     *
     * @param userLoginRequest
     * @param request
     * @return
     */
    LoginUserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request);

    /**
     * 发送短信验证码
     *
     * @param smsSendRequest 发送请求
     * @param request        HTTP请求
     * @return 是否发送成功
     */
    boolean sendSmsCode(UserSmsSendRequest smsSendRequest, HttpServletRequest request);

    /**
     * 短信验证码登录（手机号首次登录自动注册）
     *
     * @param smsLoginRequest 登录请求
     * @param request         HTTP请求
     * @return 脱敏后的登录用户信息
     */
    LoginUserVO userSmsLogin(UserSmsLoginRequest smsLoginRequest, HttpServletRequest request);

    /**
     * 设置/修改当前用户密码
     *
     * @param passwordUpdateRequest 密码请求
     * @param request               HTTP请求
     * @return 是否成功
     */
    boolean updatePassword(UserPasswordUpdateRequest passwordUpdateRequest, HttpServletRequest request);

    /**
     * 通过手机验证码重置密码（未设置过密码的用户也可以直接设置）
     *
     * @param passwordResetRequest 重置密码请求
     * @return 是否成功
     */
    boolean resetPassword(UserPasswordResetRequest passwordResetRequest);

    /**
     * 上传当前用户头像
     *
     * @param file    头像文件
     * @param request HTTP请求
     * @return 头像访问地址
     */
    String uploadAvatar(MultipartFile file, HttpServletRequest request);

    /**
     * 更新当前用户个人资料
     *
     * @param profileUpdateRequest 资料请求
     * @param request              HTTP请求
     * @return 是否成功
     */
    boolean updateProfile(UserProfileUpdateRequest profileUpdateRequest, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 根据ID获取用户信息
     * @param id
     * @return
     */
    User getUserById(long id);

    /**
     * 获取加密密码
     *
     * @param userPassword
     * @return
     */
    String getEncryptPassword(String userPassword);

    /**
     * 获得脱敏后的用户登录信息
     *
     * @param user
     * @return
     */
    LoginUserVO getLoginUserVO(User user);


    /**
     * 获得脱敏后的用户信息
     *
     * @param user
     * @return
     */
    UserVO getUserVO(User user);

    /**
     * 获得脱敏后的用户信息列表
     *
     * @param userList
     * @return 脱敏后的用户信息列表
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 用户注销
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取查询条件
     * @param userQueryRequest
     * @return
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);


    /**
     * 根据ID获取用户信息
     * @param id
     * @return
     */
    UserVO getUserVOById(long id);

    /**
     * 删除用户
     * @param deleteRequest
     * @return
     */
    boolean deleteUser(DeleteRequest deleteRequest);

    /**
     * 更新用户
     * @param user
     */
    void updateUser(User user);

    /**
     * 分页查询用户信息
     * @param userQueryRequest
     * @return
     */
    Page<UserVO> listUserVOByPage(UserQueryRequest userQueryRequest);

    /**
     * 根据用户ID列表查询用户信息
     * @param userIdSet
     * @return
     */
    List<User> listByIds(Set<Long> userIdSet);

    /**
     * 创建用户（使用默认密码）
     *
     * @param userEntity
     * @return 新用户 id
     */
    long saveUser(User userEntity);
}


