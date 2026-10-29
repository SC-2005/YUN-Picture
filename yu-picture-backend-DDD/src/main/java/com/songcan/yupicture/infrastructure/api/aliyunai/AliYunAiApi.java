package com.songcan.yupicture.infrastructure.api.aliyunai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AliYunAiApi {
    // 读取配置文件
    @Value("${aliYunAi.apiKey}")
    private String apiKey;

    // 创建任务地址
    // 老模型（image-out-painting）走 .../image2image/out-painting，已下线；
    // 现在用万相2.1通用图像编辑做扩图，走 .../image2image/image-synthesis
    public static final String CREATE_OUT_PAINTING_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/image-synthesis";

    // 查询任务状态
    public static final String GET_OUT_PAINTING_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/%s";

    /**
     * 创建任务
     *
     * @param createOutPaintingTaskRequest
     * @return
     */
    public CreateOutPaintingTaskResponse createOutPaintingTask(CreateOutPaintingTaskRequest createOutPaintingTaskRequest) {
        if (createOutPaintingTaskRequest == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图参数为空");
        }
        validBaseImageUrl(createOutPaintingTaskRequest);
        // 发送请求
        HttpRequest httpRequest = HttpRequest.post(CREATE_OUT_PAINTING_TASK_URL)
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                // 必须开启异步处理，设置为enable。
                .header("X-DashScope-Async", "enable")
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .body(JSONUtil.toJsonStr(createOutPaintingTaskRequest));
        try (HttpResponse httpResponse = httpRequest.execute()) {
            if (!httpResponse.isOk()) {
                log.error("请求异常：{}", httpResponse.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 扩图失败");
            }
            CreateOutPaintingTaskResponse response = JSONUtil.toBean(httpResponse.body(), CreateOutPaintingTaskResponse.class);
            String errorCode = response.getCode();
            if (StrUtil.isNotBlank(errorCode)) {
                String errorMessage = response.getMessage();
                log.error("AI 扩图失败，errorCode:{}, errorMessage:{}", errorCode, errorMessage);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 扩图接口响应异常");
            }
            return response;
        }
    }

    /**
     * 校验待扩图的图片地址
     * <p>DashScope 是靠链接的后缀判断图片格式的，地址没有后缀时任务一定会失败，报的还是
     * 「format of image ... is not valid」这种看不懂的错。提前拦下来，把原因说清楚，
     * 也省掉一次没意义的 AI 调用。没有后缀的图片是旧版本上传逻辑留下的，重新上传一次就好了。</p>
     */
    private void validBaseImageUrl(CreateOutPaintingTaskRequest request) {
        String baseImageUrl = request.getInput() == null ? null : request.getInput().getBaseImageUrl();
        if (StrUtil.isBlank(baseImageUrl)) {
            return;
        }
        // 去掉 ? 后面的参数再看后缀，阿里云返回的图片地址本身也是带查询参数的
        String path = StrUtil.subBefore(baseImageUrl, '?', false);
        if (StrUtil.isBlank(FileUtil.getSuffix(FileUtil.getName(path)))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "这张图片的地址里没有图片后缀，AI 扩图无法识别它，请重新上传这张图片后再试");
        }
    }

    /**
     * 查询创建的任务
     *
     * @param taskId
     * @return
     */
    public GetOutPaintingTaskResponse getOutPaintingTask(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务 id 不能为空");
        }
        try (HttpResponse httpResponse = HttpRequest.get(String.format(GET_OUT_PAINTING_TASK_URL, taskId))
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .execute()) {
            if (!httpResponse.isOk()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取任务失败");
            }
            GetOutPaintingTaskResponse response = JSONUtil.toBean(httpResponse.body(), GetOutPaintingTaskResponse.class);
            fillOutputImageUrl(response);
            return response;
        }
    }

    /**
     * 把新一代接口的任务结果统一填到 outputImageUrl 上
     * <p>老接口返回 output.output_image_url，新接口返回 output.results[].url，
     * 这里统一成同一个字段，前端就不用关心用的是哪个模型了。</p>
     */
    private void fillOutputImageUrl(GetOutPaintingTaskResponse response) {
        GetOutPaintingTaskResponse.Output output = response.getOutput();
        if (output == null || StrUtil.isNotBlank(output.getOutputImageUrl()) || CollUtil.isEmpty(output.getResults())) {
            return;
        }
        output.getResults().stream()
                .map(GetOutPaintingTaskResponse.Result::getUrl)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .ifPresent(output::setOutputImageUrl);
    }
}