package com.songcan.yupicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicturebackend.model.dto.space.SpaceAddRequest;
import com.songcan.yupicturebackend.model.dto.space.SpaceQueryRequest;
import com.songcan.yupicturebackend.model.entity.Space;
import com.baomidou.mybatisplus.extension.service.IService;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.vo.SpaceVO;

import javax.servlet.http.HttpServletRequest;

/**
 * @author 34451
 * @description 针对表【space(空间)】的数据库操作Service
 * @createDate 2026-10-13 17:20:18
 */
public interface SpaceService extends IService<Space> {

    /**
     * 创建空间
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    long addSpace(SpaceAddRequest spaceAddRequest, User loginUser);

    /**
     * 校验空间
     *
     * @param space
     */
    void validSpace(Space space,boolean add);

    /**
     * 删除空间（连同空间内的图片一起删）
     *
     * @param id 空间 id
     */
    void deleteSpace(long id);

    /**
     * 获取空间包装类(单条)
     *
     * @param space
     * @param request
     * @return
     */
    SpaceVO getSpaceVO(Space space, HttpServletRequest request);

    /**
     * 获取空间包装类(分页)
     *
     * @param spacePage
     * @param request
     * @return
     */
    Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request);


    /**
     * 获取查询对象
     * @param spaceQueryRequest
     * @return
     */
    QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest);


    /**
     * 根据空间级别填充空间对象
     * @param space
     */
    void fileSpaceBySpaceLevel(Space space);
}
