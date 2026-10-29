package com.songcan.yupicture.interfaces.dto.picture;

import lombok.Data;

import java.io.Serializable;


/**
 * 图片批量抓取请求参数
 */
@Data
public class PictureUploadByBatchRequest implements Serializable {

    /**
     * 搜索词
     */
    private String searchText;

    /**
     * 图片名称前缀
     */
    private String namePrefix;

    /**
     * 每次抓取数量
     */
    private Integer count = 10;

    /**
     * 图片分类（可选）
     * <p>
     * 留空表示「自动分类」：由后端按搜索词推断，见 PictureServiceImpl#autoCategorize。
     */
    private String category;

    private static final long serialVersionUID = 1L;
}