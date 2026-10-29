package com.songcan.yupicture.infrastructure.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.domain.user.repository.UserRepository;
import com.songcan.yupicture.infrastructure.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
 * 用户仓库实现
 */
@Service
public class UserRepositoryImpl extends ServiceImpl<UserMapper, User> implements UserRepository {
}