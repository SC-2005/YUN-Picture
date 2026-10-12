package com.songcan.yupicturebackend.manager.upload;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import com.songcan.yupicturebackend.config.CosClientConfig;
import com.songcan.yupicturebackend.exception.BusinessException;
import com.songcan.yupicturebackend.exception.ErrorCode;
import com.songcan.yupicturebackend.exception.ThrowUtils;
import com.songcan.yupicturebackend.manager.CosManager;
import com.songcan.yupicturebackend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;
import java.util.Date;
import java.util.List;


/**
 * 图片上传模板
 */
@Slf4j
public abstract class PictureUploadTemplate {

    @Resource
    private CosManager cosManager;
    @Resource
    private CosClientConfig cosClientConfig;

    /**
     * 上传图片
     *
     * @param inputSource
     * @param uploadPathPrefix
     * @return
     */
    public UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        //1.校验图片
        validPicture(inputSource);
        //2.图片上传地址
        String uuid = RandomUtil.randomString(16);
        String originalFilename = getOriginalFilename(inputSource);
        //自己拼接文件上传路径,而不是使用原始文件名称,增加安全性
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, FileUtil.getSuffix(originalFilename));
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFilename);
        File file = null;
        try {
            // 3.创建临时文件,获取文件到服务器
            file = File.createTempFile(uploadPath, null);
            //处理文件来源
            processFile(inputSource, file);
            // 4.上传文件到对象存储
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            // 5.获取图片信息对象,封装返回结果
            CIUploadResult ciUploadResult = putObjectResult.getCiUploadResult();
            ThrowUtils.throwIf(ciUploadResult == null || ciUploadResult.getOriginalInfo() == null
                            || ciUploadResult.getOriginalInfo().getImageInfo() == null,
                    ErrorCode.SYSTEM_ERROR, "获取图片信息失败，请确认对象存储已开通图片处理");
            ImageInfo imageInfo = ciUploadResult.getOriginalInfo().getImageInfo();
            //获取图片处理结果
            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if (CollUtil.isNotEmpty(objectList)) {
                //获取压缩之后得到的信息
                CIObject compressedObject = objectList.get(0);
                //缩略图默认等于压缩图
                CIObject thumbnailCiObject = compressedObject;
                //有生成的缩略图,才获取缩略图
                if(objectList.size() > 1) {
                    //获取缩略图信息
                   thumbnailCiObject = objectList.get(1);
                }
                //封装压缩图的返回结果
                return buildResult(originalFilename, compressedObject, thumbnailCiObject);
            }
            return buildResult(imageInfo, uploadPath, originalFilename, file);
        } catch (Exception e) {
            log.error("图片上传到对象存储失败", e);
            // 业务异常原样抛出，避免提示信息被覆盖
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            this.deleteTempFile(file);
        }
    }


    /**
     * 获取输入原始文件名
     *
     * @param inputSource
     * @return
     */
    protected abstract String getOriginalFilename(Object inputSource);


    /**
     * 校验输出源(本地url或者本地文件)
     *
     * @param inputSource
     */
    protected abstract void validPicture(Object inputSource);

    /**
     * 处理输入源并生成临时文件
     *
     * @param inputSource
     * @param file
     */
    protected abstract void processFile(Object inputSource, File file) throws Exception;

    /**
     * 封装返回结果
     *
     * @param originalFilename 原始文件名
     * @param compressedObject 压缩后的对象
     * @param thumbnailCiObject 缩略图对象
     * @return
     */
    private UploadPictureResult buildResult(String originalFilename, CIObject compressedObject, CIObject thumbnailCiObject) {
        // 计算宽高
        int picWidth = compressedObject.getWidth();
        int picHeight = compressedObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        // 封装返回结果
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        //设置原图地址
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedObject.getKey());
        uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
        uploadPictureResult.setPicSize(compressedObject.getSize().longValue());
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(compressedObject.getFormat());
        //设置缩略图地址
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());
        // 返回可访问的地址
        return uploadPictureResult;
    }

    /**
     * 封装返回结果
     *
     * @param imageInfo
     * @param uploadPath
     * @param originalFilename
     * @param file
     * @return
     */
    private UploadPictureResult buildResult(ImageInfo imageInfo, String uploadPath, String originalFilename, File file) {
        // 计算宽高
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        // 封装返回结果
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
        uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(imageInfo.getFormat());

        // 返回可访问的地址
        return uploadPictureResult;
    }


    /**
     * 删除临时文件
     *
     * @param file
     */
    public void deleteTempFile(File file) {
        if (file != null) {
            // 删除临时文件
            boolean deleteResult = file.delete();
            if (!deleteResult) {
                log.error("file delete error, filepath = {}", file.getAbsolutePath());
            }
        }
    }
}
