package com.songcan.yupicture.interfaces.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicture.application.service.UserApplicationService;
import com.songcan.yupicture.domain.user.constant.UserConstant;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.infrastructure.annotation.AuthCheck;
import com.songcan.yupicture.infrastructure.common.BaseResponse;
import com.songcan.yupicture.infrastructure.common.DeleteRequest;
import com.songcan.yupicture.infrastructure.common.ResultUtils;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import com.songcan.yupicture.interfaces.assembler.UserAssembler;
import com.songcan.yupicture.interfaces.dto.user.*;
import com.songcan.yupicture.interfaces.vo.user.LoginUserVO;
import com.songcan.yupicture.interfaces.vo.user.UserVO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserApplicationService userApplicationService;

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    @PostMapping("/registre")
    public BaseResponse<Long> userRegistre(@RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        long result = userApplicationService.userRegister(userRegisterRequest);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest
     * @return
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);
        LoginUserVO loginUserVO = userApplicationService.userLogin(userLoginRequest, request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 发送短信验证码
     *
     * @param smsSendRequest 发送请求
     * @return 是否发送成功
     */
    @PostMapping("/sms/send")
    public BaseResponse<Boolean> sendSmsCode(@RequestBody UserSmsSendRequest smsSendRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(smsSendRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userApplicationService.sendSmsCode(smsSendRequest, request));
    }

    /**
     * 短信验证码登录（手机号首次登录自动注册）
     *
     * @param smsLoginRequest 登录请求
     * @return 脱敏后的登录用户信息
     */
    @PostMapping("/sms/login")
    public BaseResponse<LoginUserVO> userSmsLogin(@RequestBody UserSmsLoginRequest smsLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(smsLoginRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userApplicationService.userSmsLogin(smsLoginRequest, request));
    }

    /**
     * 设置/修改当前用户密码
     *
     * @param passwordUpdateRequest 密码请求
     * @return 是否成功
     */
    @PostMapping("/password/update")
    public BaseResponse<Boolean> updatePassword(@RequestBody UserPasswordUpdateRequest passwordUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(passwordUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userApplicationService.updatePassword(passwordUpdateRequest, request));
    }

    /**
     * 通过手机验证码重置密码
     *
     * @param passwordResetRequest 重置密码请求
     * @return 是否成功
     */
    @PostMapping("/password/reset")
    public BaseResponse<Boolean> resetPassword(@RequestBody UserPasswordResetRequest passwordResetRequest) {
        ThrowUtils.throwIf(passwordResetRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userApplicationService.resetPassword(passwordResetRequest));
    }

    /**
     * 上传当前用户头像
     *
     * @param file 头像文件
     * @return 头像访问地址
     */
    @PostMapping("/avatar/upload")
    public BaseResponse<String> uploadAvatar(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return ResultUtils.success(userApplicationService.uploadAvatar(file, request));
    }

    /**
     * 更新当前用户个人资料
     *
     * @param profileUpdateRequest 资料请求
     * @return 是否成功
     */
    @PostMapping("/profile/update")
    public BaseResponse<Boolean> updateProfile(@RequestBody UserProfileUpdateRequest profileUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(profileUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userApplicationService.updateProfile(profileUpdateRequest, request));
    }

    /**
     * 获取当前登用户
     */
    @GetMapping("/get/Login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userApplicationService.getLoginUser(request);
        return ResultUtils.success(userApplicationService.getLoginUserVO(loginUser));
    }

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = userApplicationService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 创建用户
     *
     * @param userAddRequest
     * @return
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> userAdd(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        User userEntity = UserAssembler.toUserEntity(userAddRequest);
        return ResultUtils.success(userApplicationService.saveUser(userEntity));
    }

    /**
     * 根据id获取用户
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userApplicationService.getUserById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据id获取包装类
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(long id) {
        BaseResponse<User> response = getUserById(id);
        User user = response.getData();
        return ResultUtils.success(userApplicationService.getUserVO(user));
    }

    /**
     * 删除用户(管理员)
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean b = userApplicationService.deleteUser(deleteRequest);
        return ResultUtils.success(b);
    }

    /**
     * 更新用户(管理员)
     *
     * @param userUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User userEntity = UserAssembler.toUserEntity(userUpdateRequest);
        userApplicationService.updateUser(userEntity);
        return ResultUtils.success(true);
    }

    /**
     * 根据查询条件分页获取用户列表(管理员)
     *
     * @param userQueryRequest
     * @return
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userApplicationService.listUserVOByPage(userQueryRequest));
    }
}
