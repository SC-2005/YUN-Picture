package com.songcan.yupicture.application.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicture.application.service.UserApplicationService;
import com.songcan.yupicture.domain.user.constant.UserConstant;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.domain.user.service.UserDomainService;
import com.songcan.yupicture.infrastructure.api.sms.AliyunSmsApi;
import com.songcan.yupicture.infrastructure.common.DeleteRequest;
import com.songcan.yupicture.infrastructure.config.AliyunSmsConfig;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import com.songcan.yupicture.infrastructure.manager.sms.SmsCodeManager;
import com.songcan.yupicture.infrastructure.manager.upload.FilePictureUpload;
import com.songcan.yupicture.infrastructure.manager.upload.model.dto.file.UploadPictureResult;
import com.songcan.yupicture.interfaces.dto.user.UserLoginRequest;
import com.songcan.yupicture.interfaces.dto.user.UserPasswordResetRequest;
import com.songcan.yupicture.interfaces.dto.user.UserPasswordUpdateRequest;
import com.songcan.yupicture.interfaces.dto.user.UserProfileUpdateRequest;
import com.songcan.yupicture.interfaces.dto.user.UserQueryRequest;
import com.songcan.yupicture.interfaces.dto.user.UserRegisterRequest;
import com.songcan.yupicture.interfaces.dto.user.UserSmsLoginRequest;
import com.songcan.yupicture.interfaces.dto.user.UserSmsSendRequest;
import com.songcan.yupicture.interfaces.vo.user.LoginUserVO;
import com.songcan.yupicture.interfaces.vo.user.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;

/**
 * @author 34451
 * @description 针对表【user(用户)】的数据库操作Service实现
 * @createDate 2026-09-03 13:22:03
 */
@Service
@Slf4j
public class UserApplicationServiceImpl implements UserApplicationService {

    @Resource
    private UserDomainService userDomainService;

    @Resource
    private AliyunSmsApi aliyunSmsApi;

    @Resource
    private AliyunSmsConfig aliyunSmsConfig;

    @Resource
    private SmsCodeManager smsCodeManager;

    @Resource
    private FilePictureUpload filePictureUpload;

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    @Override
    public long userRegister(UserRegisterRequest userRegisterRequest) {
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        //校验
        User.validUserRegister(userAccount, userPassword, checkPassword);
        //执行注册
        return userDomainService.userRegister(userAccount, userPassword, checkPassword);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest 用户登录请求
     * @param request          HTTP请求
     * @return
     */
    @Override
    public LoginUserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request) {
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        //校验
        User.validUserLogin(userAccount, userPassword);
        //执行登录
        return userDomainService.userLogin(userAccount, userPassword, request);
    }

    /**
     * 发送短信验证码
     *
     * @param smsSendRequest 发送请求
     * @param request        HTTP请求
     * @return 是否发送成功
     */
    @Override
    public boolean sendSmsCode(UserSmsSendRequest smsSendRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(smsSendRequest == null, ErrorCode.PARAMS_ERROR);
        String phone = smsSendRequest.getPhone();
        User.validPhone(phone);
        boolean acquired = smsCodeManager.tryAcquireSendQuota(phone,
                aliyunSmsConfig.getResendIntervalSeconds(), aliyunSmsConfig.getDailyLimit());
        ThrowUtils.throwIf(!acquired, ErrorCode.OPERATION_ERROR, "验证码发送过于频繁，请稍后再试");
        String code = RandomUtil.randomNumbers(6);
        smsCodeManager.saveCode(phone, code, aliyunSmsConfig.getCodeExpireMinutes());
        try {
            aliyunSmsApi.sendVerificationCode(phone, code);
        } catch (RuntimeException e) {
            smsCodeManager.deleteCode(phone);
            throw e;
        }
        return true;
    }

    /**
     * 短信验证码登录（手机号首次登录自动注册）
     *
     * @param smsLoginRequest 登录请求
     * @param request         HTTP请求
     * @return 脱敏后的登录用户信息
     */
    @Override
    public LoginUserVO userSmsLogin(UserSmsLoginRequest smsLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(smsLoginRequest == null, ErrorCode.PARAMS_ERROR);
        String phone = smsLoginRequest.getPhone();
        String code = smsLoginRequest.getCode();
        User.validPhone(phone);
        ThrowUtils.throwIf(StrUtil.isBlank(code), ErrorCode.PARAMS_ERROR, "请输入验证码");
        boolean verified = smsCodeManager.verifyCode(phone, code);
        ThrowUtils.throwIf(!verified, ErrorCode.PARAMS_ERROR, "验证码错误或已过期");
        return userDomainService.userSmsLogin(phone, request);
    }

    /**
     * 设置/修改当前用户密码
     *
     * @param passwordUpdateRequest 密码请求
     * @param request               HTTP请求
     * @return 是否成功
     */
    @Override
    public boolean updatePassword(UserPasswordUpdateRequest passwordUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(passwordUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userDomainService.getLoginUser(request);
        User.validPassword(passwordUpdateRequest.getNewPassword(), passwordUpdateRequest.getCheckPassword());
        User user = userDomainService.getById(loginUser.getId());
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        // 已经设置过密码的用户，修改前必须先验证原密码
        if (StrUtil.isNotBlank(user.getUserPassword())) {
            String oldPassword = passwordUpdateRequest.getOldPassword();
            ThrowUtils.throwIf(StrUtil.isBlank(oldPassword), ErrorCode.PARAMS_ERROR, "请输入原密码");
            String encryptOldPassword = userDomainService.getEncryptPassword(oldPassword);
            ThrowUtils.throwIf(!encryptOldPassword.equals(user.getUserPassword()),
                    ErrorCode.PARAMS_ERROR, "原密码错误");
        }
        user.setUserPassword(userDomainService.getEncryptPassword(passwordUpdateRequest.getNewPassword()));
        boolean result = userDomainService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return true;
    }

    /**
     * 通过手机验证码重置密码（未设置过密码的用户也可以直接设置）
     *
     * @param passwordResetRequest 重置密码请求
     * @return 是否成功
     */
    @Override
    public boolean resetPassword(UserPasswordResetRequest passwordResetRequest) {
        ThrowUtils.throwIf(passwordResetRequest == null, ErrorCode.PARAMS_ERROR);
        String phone = passwordResetRequest.getPhone();
        String code = passwordResetRequest.getCode();
        User.validPhone(phone);
        ThrowUtils.throwIf(StrUtil.isBlank(code), ErrorCode.PARAMS_ERROR, "请输入验证码");
        User.validPassword(passwordResetRequest.getNewPassword(), passwordResetRequest.getCheckPassword());
        boolean verified = smsCodeManager.verifyCode(phone, code);
        ThrowUtils.throwIf(!verified, ErrorCode.PARAMS_ERROR, "验证码错误或已过期");
        User user = userDomainService.getByPhone(phone);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "该手机号还未注册，请先使用验证码登录");
        user.setUserPassword(userDomainService.getEncryptPassword(passwordResetRequest.getNewPassword()));
        boolean result = userDomainService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    /**
     * 上传当前用户头像
     *
     * @param file    头像文件
     * @param request HTTP请求
     * @return 头像访问地址
     */
    @Override
    public String uploadAvatar(MultipartFile file, HttpServletRequest request) {
        User loginUser = userDomainService.getLoginUser(request);
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择头像图片");
        UploadPictureResult uploadResult = filePictureUpload.uploadPicture(file,
                "avatar/" + loginUser.getId());
        String avatarUrl = uploadResult.getUrl();
        User updateUser = new User();
        updateUser.setId(loginUser.getId());
        updateUser.setUserAvatar(avatarUrl);
        boolean result = userDomainService.updateById(updateUser);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE,
                userDomainService.getById(loginUser.getId()));
        return avatarUrl;
    }

    /**
     * 更新当前用户个人资料
     *
     * @param profileUpdateRequest 资料请求
     * @param request              HTTP请求
     * @return 是否成功
     */
    @Override
    public boolean updateProfile(UserProfileUpdateRequest profileUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(profileUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userDomainService.getLoginUser(request);
        String userName = StrUtil.trim(profileUpdateRequest.getUserName());
        ThrowUtils.throwIf(StrUtil.isBlank(userName), ErrorCode.PARAMS_ERROR, "昵称不能为空");
        ThrowUtils.throwIf(userName.length() > 50, ErrorCode.PARAMS_ERROR, "昵称过长");
        String userAvatar = StrUtil.nullToEmpty(profileUpdateRequest.getUserAvatar());
        String userProfile = StrUtil.nullToEmpty(profileUpdateRequest.getUserProfile());
        ThrowUtils.throwIf(userAvatar.length() > 1024, ErrorCode.PARAMS_ERROR, "头像地址过长");
        ThrowUtils.throwIf(userProfile.length() > 512, ErrorCode.PARAMS_ERROR, "简介过长");
        User user = new User();
        user.setId(loginUser.getId());
        user.setUserName(userName);
        user.setUserAvatar(userAvatar);
        user.setUserProfile(userProfile);
        boolean result = userDomainService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        User updatedUser = userDomainService.getById(loginUser.getId());
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, updatedUser);
        return true;
    }

    /**
     * 获取加密密码
     *
     * @param userPassword
     * @return
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        //校验
        return userDomainService.getEncryptPassword(userPassword);
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        return userDomainService.getLoginUser(request);
    }

    /**
     * 获取脱敏类的用户信息
     *
     * @param user
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {

        return userDomainService.getLoginUserVO(user);
    }

    /**
     * 获取脱敏类的用户信息
     * @param user
     * @return
     */
    @Override
    public UserVO getUserVO(User user) {

        return userDomainService.getUserVO(user);
    }

    /**
     * 获取脱敏类的用户信息列表
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        return userDomainService.getUserVOList(userList);
    }

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        return userDomainService.userLogout(request);
    }

    /**
     * 获取查询条件
     * @param userQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        return userDomainService.getQueryWrapper(userQueryRequest);
    }


    @Override
    public User getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userDomainService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return user;
    }

    @Override
    public UserVO getUserVOById(long id) {
        return userDomainService.getUserVO(getUserById(id));
    }

    @Override
    public boolean deleteUser(DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return userDomainService.removeById(deleteRequest.getId());
    }

    @Override
    public void updateUser(User user) {
        boolean result = userDomainService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }

    @Override
    public Page<UserVO> listUserVOByPage(UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = userQueryRequest.getCurrent();
        long size = userQueryRequest.getPageSize();
        Page<User> userPage = userDomainService.page(new Page<>(current, size),
                userDomainService.getQueryWrapper(userQueryRequest));
        Page<UserVO> userVOPage = new Page<>(current, size, userPage.getTotal());
        List<UserVO> userVO = userDomainService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVO);
        return userVOPage;
    }

    @Override
    public List<User> listByIds(Set<Long> userIdSet) {
        return userDomainService.listByIds(userIdSet);
    }

    @Override
    public long saveUser(User userEntity) {
        // 默认密码
        final String DEFAULT_PASSWORD = "12345678";
        String encryptPassword = userDomainService.getEncryptPassword(DEFAULT_PASSWORD);
        userEntity.setUserPassword(encryptPassword);
        // 插入数据库
        boolean result = userDomainService.saveUser(userEntity);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return userEntity.getId();
    }

}




