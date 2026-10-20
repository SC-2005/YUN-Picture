package com.songcan.yupicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.songcan.yupicturebackend.exception.BusinessException;
import com.songcan.yupicturebackend.exception.ErrorCode;
import com.songcan.yupicturebackend.exception.ThrowUtils;
import com.songcan.yupicturebackend.model.dto.space.SpaceAddRequest;
import com.songcan.yupicturebackend.model.dto.space.SpaceQueryRequest;
import com.songcan.yupicturebackend.model.entity.Picture;
import com.songcan.yupicturebackend.model.entity.Space;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.enums.SpaceLevelEnum;
import com.songcan.yupicturebackend.model.vo.PictureVO;
import com.songcan.yupicturebackend.model.vo.SpaceVO;
import com.songcan.yupicturebackend.model.vo.UserVO;
import com.songcan.yupicturebackend.service.PictureService;
import com.songcan.yupicturebackend.service.SpaceService;
import com.songcan.yupicturebackend.mapper.SpaceMapper;
import com.songcan.yupicturebackend.service.UserService;
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
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space>
        implements SpaceService {

    /**
     * 删除空间时，每批处理的图片数量
     *
     * 一个空间最多能放 1 万张图，一条 SQL 全删的话事务太大（锁、undo、binlog 都顶不住），
     * 分批就是拿「多几条 SQL」换「单次事务小」
     */
    private static final int DELETE_PICTURE_BATCH_SIZE = 100;

    @Resource
    private UserService userService;

    /**
     * 图片服务
     *
     * 这里必须加 @Lazy：PictureServiceImpl 要注入 SpaceService 来更新空间额度，
     * 这里又要注入它来删空间下的图片，两边互为依赖。Spring Boot 2.6 之后默认禁止循环引用
     * （spring.main.allow-circular-references=false），不加 @Lazy 启动就会直接报循环依赖。
     * 加 @Lazy 后注入的是代理对象，真正用到时才去容器里取，循环就被打断了。
     */
    @Lazy
    @Resource
    private PictureService pictureService;

    @Resource
    private TransactionTemplate transactionTemplate;

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
        if (StrUtil.isBlank(space.getSpaceName())){
            space.setSpaceName("默认空间");
        }
        if (space.getSpaceLevel() == null){
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        //填充容量大小
        this.fileSpaceBySpaceLevel(space);
        //2.校验参数
        this.validSpace(space, true);
        // 3. 校验权限，非管理员只能创建普通级别的空间
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别的空间");
        }
        // 4. 控制同一用户只能创建一个私有空间
        String lock = String.valueOf(userId).intern();
        synchronized (lock) {
            Long newSpaceId = transactionTemplate.execute(status -> {
                boolean exists = this.lambdaQuery().eq(Space::getUserId, userId).exists();
                ThrowUtils.throwIf(exists, ErrorCode.OPERATION_ERROR, "每个用户仅能有一个私有空间");
                // 写入数据库
                boolean result = this.save(space);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
                // 返回新写入的数据 id
                return space.getId();
            });
            // 返回结果是包装类，可以做一些处理
            return Optional.ofNullable(newSpaceId).orElse(-1L);
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
        // 空间是图片的容器，容器没了就没必要留着里面的图：
        // 列表接口一律带上 spaceId 查询，空间删掉后这些图在任何一个页面上都查不出来，
        // 却仍然占着对象存储的容量（要花钱）和数据库的行
        List<Picture> deletedPictureList = new ArrayList<>();
        // 事务里只做数据库操作。对象存储上的文件放到事务提交后再清 ——
        // 删文件失败不该把「空间已删除」这个已经发生的事实回滚掉（和 deletePicture 同一个取舍）
        transactionTemplate.execute(status -> {
            // 分批查 + 分批删：每批 100 条，避免一次 UPDATE 上万行
            while (true) {
                List<Picture> pictureList = pictureService.lambdaQuery()
                        .eq(Picture::getSpaceId, id)
                        .last("limit " + DELETE_PICTURE_BATCH_SIZE)
                        .list();
                if (CollUtil.isEmpty(pictureList)) {
                    break;
                }
                boolean removed = pictureService.removeBatchByIds(
                        pictureList.stream().map(Picture::getId).collect(Collectors.toList()));
                ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "删除空间内的图片失败");
                // 记下这批图片，事务提交后按 url 去清理文件
                deletedPictureList.addAll(pictureList);
            }
            boolean result = this.removeById(id);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
            return true;
        });
        // 清文件：单张失败不影响其它图片，也不需要回滚数据库，记日志即可
        for (Picture picture : deletedPictureList) {
            try {
                // clearPictureFile 内部会判断这个 url 有没有被别的记录引用，被引用则不删
                pictureService.clearPictureFile(picture);
            } catch (Exception e) {
                log.error("清理空间图片文件失败，pictureId = {}", picture.getId(), e);
            }
        }
    }

    /**
     * 校验空间
     *
     * @param space
     */
    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);

        //创建时校验
        if (add) {
            // 创建空间时,空间名称不能为空
            if (StrUtil.isBlank(spaceName)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称不能为空");
            }
            //空间级别不能为空
            if (spaceLevel == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不能为空");
            }
        }
        //修改数据时,空间名称不能为空
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 30) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称长度不能超过30个字符");
        }
        //修改数据时对空间级别进行校验
        if (spaceLevel != null && spaceLevelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
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
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
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
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }


    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();
        //拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);
        //排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    /**
     * 根据空间级别填充空间对象
     *
     * @param space
     */
    @Override
    public void fileSpaceBySpaceLevel(Space space) {
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if (spaceLevelEnum != null) {
            long maxSize = spaceLevelEnum.getMaxSize();
            if (space.getMaxSize() == null) {
                space.setMaxSize(maxSize);
            }
            long maxCount = spaceLevelEnum.getMaxCount();
            if (space.getMaxCount() == null) {
                space.setMaxCount(maxCount);
            }
        }
    }

    /**
     * 校验空间权限
     * @param loginUser
     * @param space
     */
    @Override
    public void checkSpaceAuth(User loginUser, Space space) {
        // 只有管理员和本人可以编辑
        if (!space.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }
}





