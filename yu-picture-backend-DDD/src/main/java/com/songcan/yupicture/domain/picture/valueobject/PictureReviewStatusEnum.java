package com.songcan.yupicture.domain.picture.valueobject;


import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 图片盛和状态枚举
 */
@Getter
public enum PictureReviewStatusEnum {

    REVIEWING(  "待审核",  0),
    PASS( "通过",  1),
    REJECT(  "拒绝",  2);

    private final String text;

    private final int value;

    PictureReviewStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据value获取枚举
     * @param value
     * @return
     */
    public static PictureReviewStatusEnum getEnumByValue(Integer value){
        if (ObjUtil.isEmpty(value)){
            return null;
        }
        for (PictureReviewStatusEnum PictureReviewStatusEnum : PictureReviewStatusEnum.values()){
            if(PictureReviewStatusEnum.value == value){
                return PictureReviewStatusEnum;
            }
        }
        return null;
    }
}
