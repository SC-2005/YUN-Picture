package com.songcan.yupicture.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.songcan.yupicture.domain.space.entity.Space;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.interfaces.dto.space.SpaceAddRequest;
import com.songcan.yupicture.interfaces.dto.space.SpaceQueryRequest;
import com.songcan.yupicture.interfaces.vo.space.SpaceVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @author 34451
 * @description 针对表【space(空间)】的数据库操作Service
 * @createDate 2026-10-13 17:20:18
 */
public interface SpaceApplicationService extends IService<Space> {

    /**
     * 创建空间
     *
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    long addSpace(SpaceAddRequest spaceAddRequest, User loginUser);

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
     *
     * @param spaceQueryRequest
     * @return
     */
    QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest);

    /**
     * 根据空间级别填充空间对象
     *
     * @param space
     */
    void fillSpaceBySpaceLevel(Space space);

    /**
     * 校验空间权限
     *
     * @param loginUser
     * @param space
     */
    void checkSpaceAuth(User loginUser, Space space);
}
