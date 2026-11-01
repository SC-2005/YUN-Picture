package com.songcan.yupicture.interfaces.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;


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
     * 名称序号的起始值（可选，默认 0）
     * <p>
     * 批量补图时同一个分类会调用很多次，每次从 1 开始命名会重复；
     * 传入当前已有数量就能接着编号，例如已有 500 张时从 501 开始。
     */
    private Integer startIndex = 0;

    /**
     * 图片分类（可选）
     * <p>
     * 留空表示「自动分类」：由后端按搜索词推断，见 PictureServiceImpl#autoCategorize。
     */
    private String category;

    /**
     * 图片标签（可选，整批共用）
     * <p>
     * 留空表示这批图片不打标签；传入的标签会按 JSON 数组格式存进 picture.tags。
     */
    private List<String> tags;

    private static final long serialVersionUID = 1L;
}