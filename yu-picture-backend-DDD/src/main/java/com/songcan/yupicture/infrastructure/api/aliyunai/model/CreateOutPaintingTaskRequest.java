package com.songcan.yupicture.infrastructure.api.aliyunai.model;

import cn.hutool.core.annotation.Alias;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建扩图任务请求类
 */
@Data
public class CreateOutPaintingTaskRequest implements Serializable {

    /**
     * 模型
     * <p>用万相2.1通用图像编辑模型，它的 expand 功能就是"扩图"。</p>
     * <p>注意：教程原来的 image-out-painting 已经下线了（请求它会返回 Model not exist），
     * 所以换成了这个模型，接口地址、入参、返回结构都跟着变了，
     * 详见 https://help.aliyun.com/zh/model-studio/wanx-image-edit-api-reference</p>
     */
    private String model = "wanx2.1-imageedit";

    /**
     * 输入图像信息
     */
    private Input input;

    /**
     * 图像处理参数
     */
    private Parameters parameters;

    @Data
    public static class Input {
        /**
         * 必选，图像编辑功能。扩图固定传 expand
         */
        @Alias("function")
        private String function;

        /**
         * 必选，提示词，描述「扩展出来的区域画什么」，不会改变原图内容
         */
        @Alias("prompt")
        private String prompt;

        /**
         * 必选，待编辑的图像 URL（老接口叫 image_url，新接口叫 base_image_url）
         */
        @Alias("base_image_url")
        private String baseImageUrl;
    }

    @Data
    public static class Parameters implements Serializable {
        /**
         * 可选，向上扩展比例，默认值 1.0，范围 [1.0, 2.0]
         * <p>1.0 表示不扩展，2.0 表示向上扩到原来的 2 倍</p>
         */
        @Alias("top_scale")
        private Float topScale;

        /**
         * 可选，向下扩展比例，默认值 1.0，范围 [1.0, 2.0]
         */
        @Alias("bottom_scale")
        private Float bottomScale;

        /**
         * 可选，向左扩展比例，默认值 1.0，范围 [1.0, 2.0]
         */
        @Alias("left_scale")
        private Float leftScale;

        /**
         * 可选，向右扩展比例，默认值 1.0，范围 [1.0, 2.0]
         */
        @Alias("right_scale")
        private Float rightScale;

        /**
         * 可选，生成图片的数量，范围 1~4，默认 1
         */
        private Integer n;
    }
}