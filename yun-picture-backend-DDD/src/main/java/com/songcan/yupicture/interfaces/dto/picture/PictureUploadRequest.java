package com.songcan.yupicture.interfaces.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PictureUploadRequest implements Serializable {

    /**
     * 图片 id（用于修改）
     */
    private Long id;

    /**
     * 文件地址
     */
    private String fileUrl;

    /**
     * 图片名称
     */
    private String picName;

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 图片分类（批量抓取时由外层指定或自动推断，普通上传不传）
     */
    private String category;

    /**
     * 图片标签（批量抓取时由外层指定，普通上传不传）
     */
    private List<String> tags;

    private static final long serialVersionUID = 1L;
}