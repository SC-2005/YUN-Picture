package com.songcan.yupicture.application.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.songcan.yupicture.application.service.PictureApplicationService;
import com.songcan.yupicture.application.service.SpaceApplicationService;
import com.songcan.yupicture.application.service.SpaceUserApplicationService;
import com.songcan.yupicture.application.service.UserApplicationService;
import com.songcan.yupicture.domain.picture.entity.Picture;
import com.songcan.yupicture.domain.space.entity.Space;
import com.songcan.yupicture.domain.space.entity.SpaceUser;
import com.songcan.yupicture.domain.space.service.SpaceDomainService;
import com.songcan.yupicture.domain.space.valueobject.SpaceLevelEnum;
import com.songcan.yupicture.domain.space.valueobject.SpaceRoleEnum;
import com.songcan.yupicture.domain.space.valueobject.SpaceTypeEnum;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import com.songcan.yupicture.infrastructure.mapper.SpaceMapper;
import com.songcan.yupicture.interfaces.dto.space.SpaceAddRequest;
import com.songcan.yupicture.interfaces.dto.space.SpaceQueryRequest;
import com.songcan.yupicture.interfaces.vo.space.SpaceVO;
import com.songcan.yupicture.interfaces.vo.user.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author 34451
 * @description 针对表【space(空间)】的数据库操作Service实现
 * @createDate 2026-10-13 17:20:18
 */
@Slf4j
@Service
public class SpaceApplicationServiceImpl extends ServiceImpl<SpaceMapper, Space>
        implements SpaceApplicationService {

    @Resource
    private SpaceDomainService spaceDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private SpaceUserApplicationService spaceUserApplicationService;

    /**
     * 删除空间要连带删除空间内的图片，和图片服务互相依赖，用 @Lazy 打破循环
     */
    @Lazy
    @Resource
    private PictureApplicationService pictureApplicationService;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 删除空间时，每批处理的图片数量
     */
    private static final int DELETE_PICTURE_BATCH_SIZE = 100;

    /**
     * 创建空间
     *
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {
        // 1. 填充参数默认值
        //转换为实体类
        Space space = new Space();
        BeanUtils.copyProperties(spaceAddRequest, space);
        if (StrUtil.isBlank(space.getSpaceName())) {
            space.setSpaceName("默认空间");
        }
        if (space.getSpaceLevel() == null) {
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        if (space.getSpaceType() == null) {
            space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        }
        //填充容量大小
        this.fillSpaceBySpaceLevel(space);
        //2.校验参数
        space.validSpace(true);
        // 3. 校验权限，非管理员只能创建普通级别的空间
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !loginUser.isAdmin()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别的空间");
        }
        // 4. 控制同一用户只能创建一个私有空间,以及一个团队空间
        String lock = String.valueOf(userId).intern();
        synchronized (lock) {
            Long newSpaceId = transactionTemplate.execute(status -> {
                boolean exists = this.lambdaQuery()
                        .eq(Space::getUserId, userId)
                        .eq(Space::getSpaceType, space.getSpaceType())
                        .exists();
                ThrowUtils.throwIf(exists, ErrorCode.OPERATION_ERROR, "每个用户每一类空间只能创建一个");
                // 写入数据库
                boolean result = this.save(space);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "保存空间到数据库失败");
                //创建成功后,如果是团队空间,关联新增团队记录
                if (SpaceTypeEnum.TEAM.getValue() == space.getSpaceType()) {
                    SpaceUser spaceUser = new SpaceUser();
                    spaceUser.setSpaceId(space.getId());
                    spaceUser.setUserId(userId);
                    spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
                    result = spaceUserApplicationService.save(spaceUser);
                    ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建团队成员记录失败");
                }
                // 返回新写入的数据 id
                return space.getId();
            });
            ThrowUtils.throwIf(newSpaceId == null, ErrorCode.OPERATION_ERROR, "创建空间失败");
            // 返回结果是包装类，可以做一些处理
            return newSpaceId;
        }
    }

    /**
     * 删除空间（连同空间内的图片一起删）
     *
     * @param id 空间 id
     */
    @Override
    public void deleteSpace(long id) {
        // 校验空间是否存在
        Space space = this.getById(id);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        List<Picture> deletedPictureList = new ArrayList<>();
        transactionTemplate.execute(status -> {
            // 分批查 + 分批删：每批 100 条，避免一次 UPDATE 上万行
            while (true) {
                List<Picture> pictureList = pictureApplicationService.lambdaQuery()
                        .eq(Picture::getSpaceId, id)
                        .last("limit " + DELETE_PICTURE_BATCH_SIZE)
                        .list();
                if (CollUtil.isEmpty(pictureList)) {
                    break;
                }
                boolean removed = pictureApplicationService.removeBatchByIds(
                        pictureList.stream().map(Picture::getId).collect(Collectors.toList()));
                ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "删除空间内的图片失败");
                // 记下这批图片，事务提交后按 url 去清理文件
                deletedPictureList.addAll(pictureList);
            }
            boolean result = this.removeById(id);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
            // 成员记录一并删除：space_user 没有逻辑删除（为了退出后能重新加入），
            // 不清理的话空间删了成员记录还在，/spaceUser/list/my 会返回 space 为 null 的孤儿记录，
            // 前端侧边栏就会冒出「未命名团队」
            spaceUserApplicationService.remove(new QueryWrapper<SpaceUser>().eq("spaceId", id));
            return true;
        });
        // 清文件：单张失败不影响其它图片，也不需要回滚数据库，记日志即可
        for (Picture picture : deletedPictureList) {
            try {
                // clearPictureFile 内部会判断这个 url 有没有被别的记录引用，被引用则不删
                pictureApplicationService.clearPictureFile(picture);
            } catch (Exception e) {
                log.error("清理空间图片文件失败，pictureId = {}", picture.getId(), e);
            }
        }
    }

    /**
     * 获取空间封装类(单条)
     *
     * @param space
     * @param request
     * @return
     */
    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {
        // 对象转封装类
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        // 关联查询用户信息
        Long userId = space.getUserId();
        if (userId != null && userId > 0) {
            User user = userApplicationService.getUserById(userId);
            UserVO userVO = userApplicationService.getUserVO(user);
            spaceVO.setUser(userVO);
        }
        return spaceVO;
    }

    /**
     * 获取空间封装类(分页)
     *
     * @param spacePage
     * @param request
     * @return
     */
    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {
        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        if (CollUtil.isEmpty(spaceList)) {
            return spaceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream()
                .map(SpaceVO::objToVo)
                .collect(Collectors.toList());
        // 1. 关联查询用户信息
        // 1,2,3,4
        Set<Long> userIdSet = spaceList.stream().map(Space::getUserId).collect(Collectors.toSet());
        // 1 => user1, 2 => user2
        Map<Long, List<User>> userIdUserListMap = userApplicationService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userApplicationService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        return spaceDomainService.getQueryWrapper(spaceQueryRequest);
    }

    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        spaceDomainService.fillSpaceBySpaceLevel(space);
    }

    @Override
    public void checkSpaceAuth(User loginUser, Space space) {
        spaceDomainService.checkSpaceAuth(loginUser, space);
    }
}
