package com.songcan.yupicture.domain.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicture.domain.user.constant.UserConstant;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.domain.user.repository.UserRepository;
import com.songcan.yupicture.domain.user.service.UserDomainService;
import com.songcan.yupicture.domain.user.valueobject.UserRoleEnum;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import com.songcan.yupicture.interfaces.dto.user.UserQueryRequest;
import com.songcan.yupicture.interfaces.vo.user.LoginUserVO;
import com.songcan.yupicture.interfaces.vo.user.UserVO;
import com.songcan.yupicture.shared.auth.StpKit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 34451
 * @description 针对表【user(用户)】的数据库操作Service实现
 * @createDate 2026-09-03 13:22:03
 */
@Service
@Slf4j
public class UserDomainServiceImpl implements UserDomainService {

    @Resource
    private UserRepository userRepository;

    /**
     * 用户注册
     *
     * @param userAccount
     * @param userPassword
     * @param checkPassword
     * @return
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        //2.检查用户账号是否与数据库已有的重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        Long count = userRepository.getBaseMapper().selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号已存在");
        }
        //3.对密码进行加密
        String encryptPassword = getEncryptPassword(userPassword);
        //4.插入数据到数据库
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("游客");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = userRepository.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败,数据库异常");
        }
        return user.getId();
    }

    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @param request      HTTP请求
     * @return
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        //2.对用户传递的密码加密
        String encryptPassword = getEncryptPassword(userPassword);
        //3.查询数据库中这个用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = userRepository.getBaseMapper().selectOne(queryWrapper);
        //4.不存在返回错误信息
        if (user == null) {
            log.info("用户名或密码错误");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        //5.保存用户的登录状态
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        // 记录用户登录态到 Sa-token，便于空间鉴权时使用，注意保证该用户信息与 SpringSession 中的信息过期时间一致
        StpKit.SPACE.login(user.getId());
        StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    /**
     * 手机验证码登录，手机号首次登录时自动注册
     *
     * @param phone   手机号
     * @param request HTTP请求
     * @return 脱敏后的登录用户信息
     */
    @Override
    public LoginUserVO userSmsLogin(String phone, HttpServletRequest request) {
        User user = getByPhone(phone);
        if (user == null) {
            // 手机号之前可能被当作账号注册过，直接绑定到这个账号，避免重复建号
            user = getByUserAccount(phone);
            if (user != null) {
                bindPhone(user, phone);
            }
        }
        if (user == null) {
            user = new User();
            user.setUserAccount(phone);
            user.setPhone(phone);
            // 不设置默认密码，只能继续通过验证码登录
            user.setUserPassword("");
            user.setUserName("用户" + phone.substring(phone.length() - 4));
            user.setUserRole(UserRoleEnum.USER.getValue());
            boolean saveResult;
            try {
                saveResult = userRepository.save(user);
            } catch (DuplicateKeyException e) {
                // 并发首次登录时另一个请求可能已经创建成功，重新查一次即可
                user = getByPhone(phone);
                if (user == null) {
                    user = getByUserAccount(phone);
                    if (user != null) {
                        bindPhone(user, phone);
                    }
                }
                saveResult = user != null;
            }
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败,数据库异常");
            }
        }
        // 保存用户的登录状态
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        StpKit.SPACE.login(user.getId());
        StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    /**
     * 根据手机号查询用户
     *
     * @param phone 手机号
     * @return 用户
     */
    @Override
    public User getByPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return null;
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        return userRepository.getBaseMapper().selectOne(queryWrapper);
    }

    /**
     * 根据账号查询用户
     *
     * @param userAccount 账号
     * @return 用户
     */
    private User getByUserAccount(String userAccount) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        return userRepository.getBaseMapper().selectOne(queryWrapper);
    }

    /**
     * 给已有账号绑定手机号
     *
     * @param user  用户
     * @param phone 手机号
     */
    private void bindPhone(User user, String phone) {
        User updateUser = new User();
        updateUser.setId(user.getId());
        updateUser.setPhone(phone);
        userRepository.updateById(updateUser);
        user.setPhone(phone);
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        //判断是否已经登录
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        //从数据库中查询
        Long userId = currentUser.getId();
        currentUser = userRepository.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取加密密码
     *
     * @param userPassword
     * @return
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        //加盐加密
        final String SALT = "songcan";
        //md5加密
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
    }

    /**
     * 获取脱敏类的用户信息
     *
     * @param user
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        loginUserVO.setHasPassword(StrUtil.isNotBlank(user.getUserPassword()));
        return loginUserVO;
    }

    /**
     * 获取脱敏类的用户信息
     *
     * @param user
     * @return
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    /**
     * 获取脱敏类的用户信息列表
     *
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream()
                .map(this::getUserVO)
                .collect(Collectors.toList());
    }

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        //判断是否已经登录
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        //删除登录状态
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }

    /**
     * 获取查询条件
     *
     * @param userQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }

        Long id = userQueryRequest.getId();
        String userName = userQueryRequest.getUserName();
        String userAccount = userQueryRequest.getUserAccount();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), "ascend".equals(sortOrder), sortField);
        return queryWrapper;
    }

    /**
     * 删除用户
     * @param id
     * @return
     */
    @Override
    public Boolean removeById(Long id) {
        return userRepository.removeById(id);
    }

    /**
     * 更新用户
     * @param user
     * @return
     */
    @Override
    public boolean updateById(User user) {
        return userRepository.updateById(user);
    }

    /**
     * 根据ID查询用户
     * @param id
     * @return
     */
    @Override
    public User getById(long id) {
        return userRepository.getById(id);
    }

    /**
     * 分页查询用户
     * @param userPage
     * @param queryWrapper
     * @return
     */
    @Override
    public Page<User> page(Page<User> userPage, QueryWrapper<User> queryWrapper) {
        return userRepository.page(userPage, queryWrapper);
    }

    /**
     * 根据ID列表查询用户
     * @param userIdSet
     * @return
     */
    @Override
    public List<User> listByIds(Set<Long> userIdSet) {
        return userRepository.listByIds(userIdSet);
    }

    @Override
    public boolean saveUser(User userEntity) {
        return userRepository.save(userEntity);
    }
}




