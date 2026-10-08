package com.songcan.yupicturebackend.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.songcan.yupicturebackend.annotation.AuthCheck;
import com.songcan.yupicturebackend.common.BaseResponse;
import com.songcan.yupicturebackend.common.DeleteRequest;
import com.songcan.yupicturebackend.common.ResultUtils;
import com.songcan.yupicturebackend.constant.UserConstant;
import com.songcan.yupicturebackend.exception.BusinessException;
import com.songcan.yupicturebackend.exception.ErrorCode;
import com.songcan.yupicturebackend.exception.ThrowUtils;
import com.songcan.yupicturebackend.model.dto.picture.*;
import com.songcan.yupicturebackend.model.entity.Picture;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.songcan.yupicturebackend.model.vo.CursorPage;
import com.songcan.yupicturebackend.model.vo.PictureTagCategory;
import com.songcan.yupicturebackend.model.vo.PictureVO;
import com.songcan.yupicturebackend.service.PictureService;
import com.songcan.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/picture")
public class PictureController {
    @Resource
    private UserService userService;

    @Resource
    private PictureService pictureService;

    /**
     * 上传图片（可重新上传）
     *
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
        Long id = deleteRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或者管理员可删除
        if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 操作数据库
        boolean result = pictureService.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 更新图片(管理员可用)
     * @param pictureUpdateRequest
     * @param request
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatePicture(@RequestBody PictureUpdateRequest pictureUpdateRequest,HttpServletRequest request) {
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
        pictureService.fillReviewParams(oldPicture,loginUser);
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
     * 分页获取图片列表(封装类)
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        //普通用户默认只能看到审核通过的图片
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVOPage(picturePage, request));
    }

    /**
     * 游标获取图片列表(封装类)
     *
     * 和 /list/page/vo 的区别：用「上一页最后一条的 id」作游标代替 current 翻页，
     * 下拉加载时不会因为翻页期间有新图片插入而重复返回上一页末尾的行。
     *
     * 两点注意：
     * 1. 本接口固定按 id 倒序返回，不等价于按 createTime 排序。游标键就是 id，
     *    排序键必须和游标键一致，否则会漏行。
     * 2. 单次请求的耗时上限由 picture 表的总行数决定（searchText / tags 走 LIKE '%x%'，
     *    用不上索引），不由 pageSize 决定。
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
        // 普通用户默认只能看到审核通过的图片。
        // 必须在这里强制覆写：前端首页不传 reviewStatus，而 getQueryWrapper 对 null
        // 是「不加条件」，漏掉这行就等于不过滤，待审核和已拒绝的图片都会出现在首页。
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
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
        // 在此处将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));
        // 设置编辑时间
        picture.setEditTime(new Date());
        // 数据校验
        pictureService.validPicture(picture);
        User loginUser = userService.getLoginUser(request);
        //补充审核参数
        pictureService.fillReviewParams(picture,loginUser);
        // 判断是否存在
        long id = pictureEditRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 操作数据库
        boolean result = pictureService.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

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
}