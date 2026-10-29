package com.songcan.yupicture.infrastructure.manager.upload;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileTypeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import com.songcan.yupicture.infrastructure.config.CosClientConfig;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import com.songcan.yupicture.infrastructure.api.CosManager;
import com.songcan.yupicture.infrastructure.manager.upload.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;


/**
 * 图片上传模板
 */
@Slf4j
public abstract class PictureUploadTemplate {

    /**
     * 允许上传的图片后缀
     * <p>和两个子类里的入口校验保持一致，避免把改名成图片的其它文件放进来</p>
     */
    private static final List<String> ALLOWED_IMAGE_SUFFIX = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");

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
        //2.取出原始文件名，文件名里的后缀优先用（比如 1.jpg 取到 jpg）
        String originalFilename = getOriginalFilename(inputSource);
        String suffix = FileUtil.getSuffix(originalFilename);
        File file = null;
        try {
            // 3.创建临时文件,获取文件到服务器
            file = File.createTempFile("picture-upload-", null);
            //处理文件来源
            processFile(inputSource, file);
            // 4.文件名里没有后缀，就只能看文件内容本身是什么格式
            //   （URL 上传的链接经常完全不带后缀，之前这种情况会让 key 以 "." 结尾、
            //    既没有格式信息，对象存储的图片压缩处理也不会生效）
            if (StrUtil.isBlank(suffix)) {
                suffix = detectImageSuffix(file);
            }
            // 5.图片上传地址，自己拼接文件上传路径,而不是使用原始文件名称,增加安全性
            String uuid = RandomUtil.randomString(16);
            String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, suffix);
            String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFilename);
            // 6.上传文件到对象存储
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            // 7.获取图片信息对象,封装返回结果
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
                return buildResult(originalFilename, compressedObject, thumbnailCiObject,imageInfo);
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
     * 根据文件内容判断图片后缀
     * <p>对象存储的 key 一定要带上图片后缀：不带后缀时访问返回的 Content-Type 是
     * application/octet-stream，不只是浏览器容易出问题，阿里云百炼那边也会直接判定
     * 「format of image ... is not valid」而不肯扩图。</p>
     *
     * @param file 已经下载好的图片文件
     * @return 图片后缀，比如 jpg
     */
    private String detectImageSuffix(File file) {
        String fileType = FileTypeUtil.getType(file);
        if (!ALLOWED_IMAGE_SUFFIX.contains(fileType)) {
            log.error("无法识别图片格式，识别结果 = {}，文件 = {}", fileType, file.getAbsolutePath());
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无法识别图片格式，请换一张图片");
        }
        return fileType;
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
    private UploadPictureResult buildResult(String originalFilename, CIObject compressedObject, CIObject thumbnailCiObject,ImageInfo imageInfo) {
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
        uploadPictureResult.setPicColor(imageInfo.getAve());
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
        uploadPictureResult.setPicColor(imageInfo.getAve());

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
