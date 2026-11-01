package com.songcan.yupicture.infrastructure.api.aliyunai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    // 视觉模型（OpenAI 兼容接口）：看图生成名称和标签
    public static final String CHAT_COMPLETION_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private static final String DESCRIBE_PROMPT =
            "请仔细看这张图片。如果画面里能明确认出是动漫角色、游戏角色或有名的明星，名称就直接用该角色或明星的名字"
                    + "（例如：初音未来、雷电将军）；认不出来或者不确定时千万不要猜，用简短的画面描述。"
                    + "名称一律不超过5个字，角色或明星名字较长时用常用简称（例如「蒙奇·D·路飞」写成「路飞」）。"
                    + "再给2-4个中文标签；能认出角色或明星时，标签里带上作品名（例如：原神、海贼王）"
                    + "或「动漫人物」「游戏角色」「明星」这类词。只输出 JSON：{\"name\":\"...\",\"tags\":[\"...\",\"...\"]}";

    // 视觉模型名称，默认用当前可用的 qwen3-vl-plus
    @Value("${aliYunAi.vlModel:qwen3-vl-plus}")
    private String vlModel;

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

    /**
     * 让视觉模型看图，生成名称和标签。
     * <p>
     * 图片以 base64 data URL 传给 DashScope，而不是直接传 COS 链接：
     * COS 开了 Referer 防盗链，阿里云侧拉图会被拒（Failed to download multimodal content）。
     * <p>
     * 失败返回 null，由调用方决定跳过还是稍后重试。
     */
    public ImageDescription describeImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String mime = StrUtil.blankToDefault(mimeType, "image/jpeg");
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        JSONObject imageUrl = new JSONObject();
        imageUrl.set("url", dataUrl);
        JSONObject imagePart = new JSONObject();
        imagePart.set("type", "image_url");
        imagePart.set("image_url", imageUrl);
        JSONObject textPart = new JSONObject();
        textPart.set("type", "text");
        textPart.set("text", DESCRIBE_PROMPT);
        JSONArray content = new JSONArray();
        content.add(imagePart);
        content.add(textPart);
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", content);
        JSONArray messages = new JSONArray();
        messages.add(message);
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", vlModel);
        requestBody.set("messages", messages);
        requestBody.set("temperature", 0.2);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (HttpResponse httpResponse = HttpRequest.post(CHAT_COMPLETION_URL)
                    .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                    .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                    .timeout(90000)
                    .body(requestBody.toString())
                    .execute()) {
                if (httpResponse.isOk()) {
                    return parseImageDescription(httpResponse.body());
                }
                int status = httpResponse.getStatus();
                log.warn("视觉模型调用失败，status={}，第 {} 次尝试，响应：{}",
                        status, attempt, StrUtil.subPre(httpResponse.body(), 500));
                // 4xx（限流 429 除外）是参数或图片本身的问题，重试也没用
                if (status >= 400 && status < 500 && status != 429) {
                    return null;
                }
            } catch (Exception e) {
                log.warn("视觉模型调用异常，第 {} 次尝试：{}", attempt, e.getMessage());
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 解析视觉模型返回的 JSON。模型偶尔会包一层 ```json，这里兼容。
     */
    private ImageDescription parseImageDescription(String responseBody) {
        try {
            JSONArray choices = JSONUtil.parseObj(responseBody).getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            String content = choices.getJSONObject(0).getJSONObject("message").getStr("content");
            if (StrUtil.isBlank(content)) {
                return null;
            }
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JSONObject json = JSONUtil.parseObj(content.substring(start, end + 1));
            String name = StrUtil.trimToNull(json.getStr("name"));
            if (name == null) {
                return null;
            }
            List<String> tags = new ArrayList<>();
            JSONArray tagArray = json.getJSONArray("tags");
            if (tagArray != null) {
                for (Object tag : tagArray) {
                    String value = StrUtil.trimToNull(String.valueOf(tag));
                    if (value != null) {
                        tags.add(value);
                    }
                }
            }
            return new ImageDescription(name, tags);
        } catch (Exception e) {
            log.warn("解析视觉模型结果失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 视觉模型给出的图片描述
     */
    public static class ImageDescription {
        private final String name;
        private final List<String> tags;

        public ImageDescription(String name, List<String> tags) {
            this.name = name;
            this.tags = tags;
        }

        public String getName() {
            return name;
        }

        public List<String> getTags() {
            return tags;
        }
    }
}