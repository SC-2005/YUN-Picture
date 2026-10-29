package com.songcan.yupicture.domain.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.interfaces.dto.user.UserQueryRequest;
import com.songcan.yupicture.interfaces.vo.user.LoginUserVO;
import com.songcan.yupicture.interfaces.vo.user.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;

/**
 * @author 34451
 * @description 针对表【user(用户)】的数据库操作Service
 * @createDate 2026-09-03 13:22:03
 */
public interface UserDomainService {

    /**
     * 用户注册
     *
     * @param userAccount
     * @param userPassword
     * @param checkPassword
     * @return
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     *
     * @param userAccount
     * @param userPassword
     * @param request
     * @return
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 手机验证码登录，手机号首次登录时自动注册
     *
     * @param phone   手机号
     * @param request HTTP请求
     * @return 脱敏后的登录用户信息
     */
    LoginUserVO userSmsLogin(String phone, HttpServletRequest request);

    /**
     * 根据手机号查询用户
     *
     * @param phone 手机号
     * @return 用户
     */
    User getByPhone(String phone);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

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
     * 删除用户
     * @param id
     * @return
     */
    Boolean removeById(Long id);

    /**
     * 更新用户
     * @param user
     * @return
     */
    boolean updateById(User user);

    /**
     * 根据ID查询用户
     * @param id
     * @return
     */
    User getById(long id);

    /**
     * 分页查询用户
     * @param userPage
     * @param queryWrapper
     * @return
     */
    Page<User> page(Page<User> userPage, QueryWrapper<User> queryWrapper);

    /**
     * 根据ID集合查询用户
     * @param userIdSet
     * @return
     */
    List<User> listByIds(Set<Long> userIdSet);

    /**
     * 保存用户
     *
     * @param userEntity
     * @return
     */
    boolean saveUser(User userEntity);
}


