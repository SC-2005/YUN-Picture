package com.songcan.yupicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicturebackend.model.dto.space.SpaceAddRequest;
import com.songcan.yupicturebackend.model.dto.space.SpaceQueryRequest;
import com.songcan.yupicturebackend.model.dto.spaceuser.SpaceUserAddRequest;
import com.songcan.yupicturebackend.model.dto.spaceuser.SpaceUserQueryRequest;
import com.songcan.yupicturebackend.model.entity.Space;
import com.songcan.yupicturebackend.model.entity.SpaceUser;
import com.baomidou.mybatisplus.extension.service.IService;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.vo.SpaceUserVO;
import com.songcan.yupicturebackend.model.vo.SpaceVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author 34451
* @description 针对表【space_user(空间用户关联)】的数据库操作Service
* @createDate 2026-10-21 18:10:49
*/
public interface SpaceUserService extends IService<SpaceUser> {
    /**
     * 创建空间成员
     *
     * @param spaceUserAddRequest
     * @return
     */
    long addSpaceUser(SpaceUserAddRequest spaceUserAddRequest);

    /**
     * 校验空间成员
     *
     * @param spaceUser
     */
    void validSpaceUser(SpaceUser spaceUser, boolean add);

    /**
     * 获取空间成员包装类(单条)
     *
     * @param spaceUser
     * @param request
     * @return
     */
    SpaceUserVO getSpaceUserVO(SpaceUser spaceUser, HttpServletRequest request);

    /**
     * 获取空间成员包装类(列表)
     *
     * @param spaceUserList
     * @return
     */
    List<SpaceUserVO> getSpaceUserVOList(List<SpaceUser> spaceUserList);


    /**
     * 获取查询对象
     *
     * @param spaceUserQueryRequest
     * @return
     */
    QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest spaceUserQueryRequest);

}
