package com.songcan.yupicturebackend.manager;

import cn.hutool.core.io.FileUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.*;
import com.qcloud.cos.model.ciModel.job.PicProcessOperation;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import com.songcan.yupicturebackend.config.CosClientConfig;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
public class CosManager {

    @Resource
    private COSClient cosClient;
    @Resource
    private CosClientConfig cosClientConfig;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 下载对象
     *
     * @param key
     * @return
     */
    public COSObject getObject(String key) {
        GetObjectRequest getObjectAclRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectAclRequest);
    }

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putPictureObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        //对图片进行处理
        PicOperations picOperation = new PicOperations();
        // 1.返回原图信息
        picOperation.setIsPicInfo(1);
        //图片处理规则
        List<PicOperations.Rule> rules = new ArrayList<>();
        //1. 图片压缩为webp格式
        String webpKey = FileUtil.mainName(key) + ".webp";
        PicOperations.Rule comperessRule = new PicOperations.Rule();
        comperessRule.setFileId(webpKey);
        comperessRule.setBucket(cosClientConfig.getBucket());
        comperessRule.setRule("imageMogr2/format/webp");
        picOperation.setRules(rules);
        rules.add(comperessRule);
        // 2.缩略图处理
        if (file.length() > 2 * 1024) {
            PicOperations.Rule thumbnailRule = new PicOperations.Rule();
            //拼接缩略图路径
            String thumbnailKey = FileUtil.mainName(key) + "_thumbnail." + FileUtil.getSuffix(key);
            thumbnailRule.setFileId(thumbnailKey);
            thumbnailRule.setBucket(cosClientConfig.getBucket());
            // 缩放规则 /thumbnail/<Width>x<Height>>（如果大于原图宽高，则不处理）
            thumbnailRule.setRule(String.format("imageMogr2/thumbnail/%sx%s>", 256, 256));
            rules.add(thumbnailRule);
        }
        //构造参数
        putObjectRequest.setPicOperations(picOperation);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 删除对象
     *
     * @param key 唯一键
     *
     */
    public void deleteObject(String key) {
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }
}
