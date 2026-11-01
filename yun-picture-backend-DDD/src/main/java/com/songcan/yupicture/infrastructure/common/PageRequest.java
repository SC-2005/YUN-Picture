package com.songcan.yupicture.infrastructure.common;

import lombok.Data;


/**
 * 分页请求类
 */
@Data
public class PageRequest {

    /**
     * 当前页
     */
    private int current = 1;

    /**
     * 每页大小
     */
    private int pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序方式
     */
    private String sortOrder = "descend";
}
