package com.songcan.yupicture.interfaces.dto.picture;

import com.songcan.yupicture.infrastructure.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;


/**
 * 图片审核请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class PictureReviewRequest extends PageRequest implements Serializable {

    /**
     * 图片id
     */
    private Long id;

    /**
     * 审核状态: 0-待审核; 1-通过; 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    private String reviewMessage;

    private static final long serialVersionUID = 1L;


}