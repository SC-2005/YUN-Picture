package com.songcan.yupicture.domain.space.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.songcan.yupicture.domain.space.entity.SpaceUser;
import com.songcan.yupicture.interfaces.dto.spaceuser.SpaceUserQueryRequest;

/**
 * @author 34451
 * @description 针对表【space_user(空间用户关联)】的数据库操作Service
 * @createDate 2026-10-21 18:10:49
 */
public interface SpaceUserDomainService {

    /**
     * 获取查询对象
     *
     * @param spaceUserQueryRequest
     * @return
     */
    QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest spaceUserQueryRequest);
}
