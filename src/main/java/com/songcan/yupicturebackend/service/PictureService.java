package com.songcan.yupicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.songcan.yupicturebackend.model.dto.picture.PictureQueryRequest;
import com.songcan.yupicturebackend.model.dto.picture.PictureReviewRequest;
import com.songcan.yupicturebackend.model.dto.picture.PictureUploadByBatchRequest;
import com.songcan.yupicturebackend.model.dto.picture.PictureUploadRequest;
import com.songcan.yupicturebackend.model.dto.user.UserQueryRequest;
import com.songcan.yupicturebackend.model.entity.Picture;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.vo.PictureVO;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author 34451
* @description 针对表【picture(图片)】的数据库操作Service
* @createDate 2026-09-09 19:15:40
*/
public interface PictureService extends IService<Picture> {


    /**
     * 校验图片
     * @param picture
     */
    void validPicture(Picture picture);

    /**
     * 上传图片
     *
     * @param inputSource
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser);

    /**
     * 获取图片包装类(单条)
     * @param picture
     * @param request
     * @return
     */
    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    /**
     * 获取图片包装类(分页)
     * @param picturePage
     * @param request
     * @return
     */
    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    /**
     * 获取图片包装类(列表)
     * @param pictureList
     * @return
     */
    List<PictureVO> getPictureVOList(List<Picture> pictureList);

    /**
     * 获取查询对象
     * @param pictureQueryRequest
     * @return
     */
    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    /**
     * 图片审核
     * @param pictureReviewRequest
     * @param loginUser
     */
    void doPictureReview(PictureReviewRequest  pictureReviewRequest,User loginUser);

    /**
     * 填充审核参数
     * @param picture
     * @param loginUser
     */
    void fillReviewParams(Picture picture,User loginUser);

    /**
     * 按搜索词自动推断图片分类
     *
     * @param searchText 搜索词
     * @return 推断出的分类；searchText 为空时返回 null
     */
    String autoCategorize(String searchText);

    /**
     * 批量抓取图片
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return
     */
    Integer UploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser);

    /**
     * 清理图片文件
     * @param oldPicture
     */
    void clearPictureFile(Picture oldPicture);
}
