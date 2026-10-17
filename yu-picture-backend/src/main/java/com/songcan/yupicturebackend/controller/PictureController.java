package com.songcan.yupicturebackend.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.songcan.yupicturebackend.annotation.AuthCheck;
import com.songcan.yupicturebackend.api.aliyunai.AliYunAiApi;
import com.songcan.yupicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.songcan.yupicturebackend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.songcan.yupicturebackend.common.BaseResponse;
import com.songcan.yupicturebackend.common.DeleteRequest;
import com.songcan.yupicturebackend.common.ResultUtils;
import com.songcan.yupicturebackend.constant.UserConstant;
import com.songcan.yupicturebackend.exception.BusinessException;
import com.songcan.yupicturebackend.exception.ErrorCode;
import com.songcan.yupicturebackend.exception.ThrowUtils;
import com.songcan.yupicturebackend.model.dto.picture.*;
import com.songcan.yupicturebackend.model.entity.Picture;
import com.songcan.yupicturebackend.model.entity.Space;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.songcan.yupicturebackend.model.vo.CursorPage;
import com.songcan.yupicturebackend.model.vo.PictureTagCategory;
import com.songcan.yupicturebackend.model.vo.PictureVO;
import com.songcan.yupicturebackend.service.PictureService;
import com.songcan.yupicturebackend.service.SpaceService;
import com.songcan.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/picture")
public class PictureController {
    @Resource
    private UserService userService;

    @Resource
    private PictureService pictureService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SpaceService spaceService;

    @Resource
    private AliYunAiApi aliYunAiApi;
    /**
     * 本地缓存
     */
    private final Cache<String, String> LOCAL_CACHE = Caffeine.newBuilder()
            .initialCapacity(1024)
            .maximumSize(10_000L) // 最大 10000 条
            // 缓存 5 分钟后移除
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * 上传图片（可重新上传）
     * <p>
     * 不限制角色，只要求登录：非管理员上传的图片会被 fillReviewParams 置为「待审核」，
     * 审核通过前不会出现在首页（首页列表强制 reviewStatus = PASS）。
     */
    @PostMapping("/upload")
    public BaseResponse<PictureVO> uploadPicture(
            @RequestPart("file") MultipartFile multipartFile,
            PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }

    /**
     * 通过url上传图片（可重新上传）
     *
     *
     */
    @PostMapping("/upload/url")
    public BaseResponse<PictureVO> uploadPictureByUrl(
            @RequestBody PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        String fileUrl = pictureUploadRequest.getFileUrl();
        PictureVO pictureVO = pictureService.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }

    /**
     * 删除图片
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletePicture(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        pictureService.deletePicture(deleteRequest.getId(),loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 更新图片(管理员可用)
     *
     * @param pictureUpdateRequest
     * @param request
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatePicture(@RequestBody PictureUpdateRequest pictureUpdateRequest, HttpServletRequest request) {
        if (pictureUpdateRequest == null || pictureUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureUpdateRequest.getTags()));
        // 数据校验
        pictureService.validPicture(picture);
        // 判断是否存在
        long id = pictureUpdateRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        //补充审核参数
        User loginUser = userService.getLoginUser(request);
        pictureService.fillReviewParams(oldPicture, loginUser);
        // 操作数据库
        boolean result = pictureService.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取图片（仅管理员可用）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Picture> getPictureById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(picture);
    }

    /**
     * 根据 id 获取图片(封装类)
     */
    @GetMapping("/get/vo")
    public BaseResponse<PictureVO> getPictureVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验空间权限
        Long spaceId = picture.getSpaceId();
        if (spaceId != null) {
            User loginUser = userService.getLoginUser(request);
            pictureService.checkPictureAuth(loginUser, picture);
        }
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVO(picture, request));
    }

    /**
     * 分页获取图片列表（仅管理员可用）
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Picture>> listPictureByPage(@RequestBody PictureQueryRequest pictureQueryRequest) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        return ResultUtils.success(picturePage);
    }

    /**
     * 公共图库 / 私有空间的查询前置处理
     *
     * 「列图片」的几个接口面向的是同一件事，过滤条件和权限规则必须完全一致，所以收敛到这一处：
     * - 不带 spaceId：公共图库，只返回「已过审」且「不属于任何空间」的图片；
     * - 带 spaceId：私有空间，先校验空间归属，再清掉审核状态过滤。
     *
     * 单独抽出来是因为踩过坑：/list/cursor 以前只加了审核状态、漏了 nullSpaceId，
     * 结果管理员上传到自己空间的图片（管理员上传自动过审，reviewStatus 就是 PASS）
     * 直接被首页公共图库查了出来，谁都能看到
     *
     * @param pictureQueryRequest 查询请求，方法内会就地补上 / 清掉限制条件
     * @param request             用来取当前登录用户
     */
    private void prepareGalleryQuery(PictureQueryRequest pictureQueryRequest, HttpServletRequest request) {
        Long spaceId = pictureQueryRequest.getSpaceId();
        // 公共图库
        if (spaceId == null) {
            // 普通用户默认只能查看已过审的公开数据。
            // 必须显式覆写：前端不传 reviewStatus，而 getQueryWrapper 对 null 是「不加条件」
            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            // 关键的一条：公共图库只包含「没有归属空间」的图片。
            // 少了它，私有空间里的图片会被一起查出来（管理员上传的图自动过审，正好绕过审核这道闸）
            pictureQueryRequest.setNullSpaceId(true);
            return;
        }
        // 私有空间：不能过滤审核状态。非管理员上传到空间的图片是「待审核」，
        // 一旦在这里过滤掉，用户在自己空间里就看不到刚上传的图；私有空间本来就不需要审核
        pictureQueryRequest.setReviewStatus(null);
        // 客户端可能自己把这个条件带进来，必须清掉，否则会拼出「spaceId is null and spaceId = ?」
        pictureQueryRequest.setNullSpaceId(false);
        // 空间权限校验
        User loginUser = userService.getLoginUser(request);
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (!loginUser.getId().equals(space.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
        }
    }

    /**
     * 分页获取图片列表(封装类)
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        // 公共图库 / 私有空间的过滤条件 + 空间权限校验
        this.prepareGalleryQuery(pictureQueryRequest, request);
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVOPage(picturePage, request));
    }


    /**
     * 分页获取图片列表(封装类,有缓存)
     */
    @Deprecated
    @PostMapping("/list/page/vo/cache")
    public BaseResponse<Page<PictureVO>> listPictureVOByPageWithCache(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                                      HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        // 公共图库 / 私有空间的过滤条件 + 空间权限校验（和 /list/page/vo 共用同一套规则）
        this.prepareGalleryQuery(pictureQueryRequest, request);
        //查询缓存,缓存没有查询数据库
        //构建缓存key
        String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
        String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes());
        String cacheKey = String.format("listPictureVOByPage:%s", hashKey);
        //1.先查本地缓存中
        String cachedValue = LOCAL_CACHE.getIfPresent(cacheKey);
        if(cachedValue != null){
            //如果缓存命中,直接返回缓存结果
            Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
            return ResultUtils.success(cachedPage);
        }
        //2.本地缓存未命中,查询redis分布式缓存
        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        cachedValue = opsForValue.get(cacheKey);
        if (cachedValue != null) {
            //如果缓存命中,直接返回缓存结果
            LOCAL_CACHE.put(cacheKey, cachedValue);
            Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
            return ResultUtils.success(cachedPage);
        }
        //3. 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
        //4.更新缓存
        //更新redis缓存
        String cacheValue = JSONUtil.toJsonStr(pictureVOPage);
        //设置缓存过期时间5-10分钟,防止缓存雪崩
        int cacheExpireTime = 300 + RandomUtil.randomInt(0, 300);
        opsForValue.set(cacheKey, cacheValue, cacheExpireTime, TimeUnit.SECONDS);
        //更新本地本地缓存
        LOCAL_CACHE.put(cacheKey, cacheValue);
        // 获取封装类
        return ResultUtils.success(pictureVOPage);
    }


    /**
     * 游标获取图片列表(封装类)
     * <p>
     * 和 /list/page/vo 的区别：用「上一页最后一条的 id」作游标代替 current 翻页，
     * 下拉加载时不会因为翻页期间有新图片插入而重复返回上一页末尾的行。
     * <p>
     * 两点注意：
     * 1. 本接口固定按 id 倒序返回，不等价于按 createTime 排序。游标键就是 id，
     * 排序键必须和游标键一致，否则会漏行。
     * 2. 单次请求的耗时上限由 picture 表的总行数决定（searchText / tags 走 LIKE '%x%'，
     * 用不上索引），不由 pageSize 决定。
     */
    @PostMapping("/list/cursor")
    public BaseResponse<CursorPage<PictureVO>> listPictureVOByCursor(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                                     HttpServletRequest request) {
        long size = pictureQueryRequest.getPageSize();
        Long cursor = pictureQueryRequest.getCursor();
        // 限制爬虫；同时挡住 pageSize <= 0 —— 那种情况下会查出空页却报告 hasMore，前端必然死循环
        ThrowUtils.throwIf(size <= 0 || size > 20, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(cursor != null && cursor <= 0, ErrorCode.PARAMS_ERROR);
        // 强制按 id 排序：排序键必须和游标键一致
        pictureQueryRequest.setSortField("id");
        pictureQueryRequest.setSortOrder("descend");
        // 公共图库 / 私有空间的过滤条件 + 空间权限校验（和 /list/page/vo 共用同一套规则）
        this.prepareGalleryQuery(pictureQueryRequest, request);
        QueryWrapper<Picture> queryWrapper = pictureService.getQueryWrapper(pictureQueryRequest);
        // 游标条件：只取 id 比上一页最后一条更小的记录
        queryWrapper.lt(cursor != null, "id", cursor);
        // 多查 1 条用来判断还有没有下一页；第三个参数 false = 不额外发 COUNT 查询
        Page<Picture> picturePage = pictureService.page(new Page<>(1, size + 1, false), queryWrapper);
        List<Picture> records = picturePage.getRecords();
        boolean hasMore = records.size() > size;
        if (hasMore) {
            // 复制一份，不要留下 subList 视图
            records = new ArrayList<>(records.subList(0, (int) size));
        }
        Long nextCursor = CollUtil.isEmpty(records) ? null : records.get(records.size() - 1).getId();
        if (nextCursor == null) {
            // 兜底：拿不到游标就不可能继续翻页，必须把 hasMore 压成 false。
            // 这是防「hasMore 一直为 true 但永远没有新数据」死循环的最后一道闸
            hasMore = false;
        }
        CursorPage<PictureVO> cursorPage = new CursorPage<>();
        cursorPage.setRecords(pictureService.getPictureVOList(records));
        cursorPage.setNextCursor(nextCursor);
        cursorPage.setHasMore(hasMore);
        return ResultUtils.success(cursorPage);
    }

    /**
     * 编辑图片（给用户使用）
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editPicture(@RequestBody PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        if (pictureEditRequest == null || pictureEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        pictureService.editPicture(pictureEditRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 获取图片标签和分类
     *
     * @return
     */
    @GetMapping("/tag_category")
    public BaseResponse<PictureTagCategory> ListPictureTagCategory() {
        PictureTagCategory pictureTagCategory = new PictureTagCategory();
        List<String> tagList = Arrays.asList("热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "简历", "创意");
        List<String> categoryList = Arrays.asList("模板", "电商", "表情包", "素材", "海报");
        pictureTagCategory.setTagList(tagList);
        pictureTagCategory.setCategoryList(categoryList);
        return ResultUtils.success(pictureTagCategory);
    }

    /**
     * 图片审核
     */
    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> doPictureReview(@RequestBody PictureReviewRequest PictureReviewRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(PictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.doPictureReview(PictureReviewRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 批量抓取图片
     */
    @PostMapping("/upload/batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> UploadPictureByBatch(@RequestBody PictureUploadByBatchRequest pictureUploadByBatchRequest,
                                                      HttpServletRequest request) {
        ThrowUtils.throwIf(pictureUploadByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Integer uploadCount = pictureService.UploadPictureByBatch(pictureUploadByBatchRequest, loginUser);
        return ResultUtils.success(uploadCount);
    }

    /**
     * 批量编辑图片（分类、标签、名称）
     * <p>
     * 只给私有空间用：请求里必须带 spaceId，服务层会按空间归属校验权限
     */
    @PostMapping("/edit/batch")
    public BaseResponse<Boolean> editPictureByBatch(@RequestBody PictureEditByBatchRequest pictureEditByBatchRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(pictureEditByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.editPictureByBatch(pictureEditByBatchRequest, loginUser);
        return ResultUtils.success(true);
    }

    @PostMapping("/search/color")
    public BaseResponse<List<PictureVO>> searchPictureByColor(@RequestBody SearchPictureByColorRequest searchPictureByColorRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(searchPictureByColorRequest == null, ErrorCode.PARAMS_ERROR);
        String picColor = searchPictureByColorRequest.getPicColor();
        Long spaceId = searchPictureByColorRequest.getSpaceId();
        User loginUser = userService.getLoginUser(request);
        List<PictureVO> result = pictureService.searchPictureByColor(spaceId, picColor, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 创建 AI 扩图任务
     */
    @PostMapping("/out_painting/create_task")
    public BaseResponse<CreateOutPaintingTaskResponse> createPictureOutPaintingTask(
            @RequestBody CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest,
            HttpServletRequest request) {
        if (createPictureOutPaintingTaskRequest == null || createPictureOutPaintingTaskRequest.getPictureId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        CreateOutPaintingTaskResponse response = pictureService.createPictureOutPaintingTask(createPictureOutPaintingTaskRequest, loginUser);
        return ResultUtils.success(response);
    }

    /**
     * 查询 AI 扩图任务
     */
    @GetMapping("/out_painting/get_task")
    public BaseResponse<GetOutPaintingTaskResponse> getPictureOutPaintingTask(String taskId) {
        ThrowUtils.throwIf(StrUtil.isBlank(taskId), ErrorCode.PARAMS_ERROR);
        GetOutPaintingTaskResponse task = aliYunAiApi.getOutPaintingTask(taskId);
        return ResultUtils.success(task);
    }
}