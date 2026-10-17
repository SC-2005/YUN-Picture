package com.songcan.yupicturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 游标分页结果
 *
 * 用游标（上一页最后一条的 id）代替 offset（current * pageSize）翻页：
 * offset 分页在「翻页期间有新数据插入」时会重复返回上一页末尾的行、甚至漏行，
 * 游标分页不会。
 *
 * 注意：JsonConfig 给 Long 注册了 ToStringSerializer，所以 nextCursor 在 JSON 里是字符串。
 * 前端必须原样透传，不能转成 number —— 雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度。
 *
 * @param <T> 记录类型
 */
@Data
public class CursorPage<T> implements Serializable {

    /**
     * 当前页数据，保证不为 null
     */
    private List<T> records = Collections.emptyList();

    /**
     * 下一页游标（本页最后一条的 id），为 null 表示没有更多数据
     */
    private Long nextCursor;

    /**
     * 是否还有更多数据
     */
    private boolean hasMore;

    private static final long serialVersionUID = 1L;
}
