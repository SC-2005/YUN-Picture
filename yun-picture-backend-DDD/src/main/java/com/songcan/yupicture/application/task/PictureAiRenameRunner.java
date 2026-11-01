package com.songcan.yupicture.application.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.songcan.yupicture.domain.picture.entity.Picture;
import com.songcan.yupicture.domain.picture.repository.PictureRepository;
import com.songcan.yupicture.infrastructure.api.aliyunai.AliYunAiApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性任务：让视觉模型看图片内容，重新生成图片名称和标签。
 * <p>
 * 默认不启用，配置 {@code picture.ai-rename.enabled=true} 才会在启动后跑后台线程。
 * 只处理「易烊千玺」分类以外的图片；已处理过的图片 ID 记在 Redis 集合里，
 * 进程重启会跳过已完成的部分，不会重复调用（也就不会重复花钱）。
 * <p>
 * 图片先由后端带 Referer 下载下来再以 base64 传给模型：COS 开了 Referer 防盗链，
 * 直接让云端拉链接会被 403。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "picture.ai-rename", name = "enabled", havingValue = "true")
public class PictureAiRenameRunner implements ApplicationRunner {

    /**
     * 已处理图片 ID 的 Redis 集合
     */
    private static final String DONE_KEY = "picture:ai-rename:done";

    /**
     * 名称最长保留多少字（用户要求不超过 5 个字，模型万一超了这里兜底截断）
     */
    private static final int NAME_MAX_LENGTH = 5;

    /**
     * 单个标签最长多少字、最多保留几个
     */
    private static final int TAG_MAX_LENGTH = 12;
    private static final int TAG_MAX_COUNT = 4;

    /**
     * 原图超过这个大小就改用缩略图，避免 base64 请求体过大
     */
    private static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;

    @Value("${picture.ai-rename.concurrency:4}")
    private int concurrency;

    /**
     * COS 开了 Referer 防盗链，下载时必须带站点 Referer
     */
    @Value("${picture.ai-rename.referer:http://123.207.58.189/}")
    private String referer;

    @Resource
    private PictureRepository pictureRepository;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AliYunAiApi aliYunAiApi;

    private final AtomicInteger okCount = new AtomicInteger();
    private final AtomicInteger failCount = new AtomicInteger();
    private final AtomicInteger finishedCount = new AtomicInteger();

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(this::renameAll, "picture-ai-rename");
        thread.setDaemon(false);
        thread.start();
    }

    private void renameAll() {
        long startTime = System.currentTimeMillis();
        log.info("========== AI 图片重命名任务启动 ==========");
        try {
            List<Picture> all = pictureRepository.lambdaQuery()
                    .ne(Picture::getCategory, "易烊千玺")
                    .list();
            List<Picture> pending = new ArrayList<>();
            for (Picture picture : all) {
                if (isJacksonYee(picture)) {
                    continue;
                }
                if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                        .isMember(DONE_KEY, String.valueOf(picture.getId())))) {
                    continue;
                }
                pending.add(picture);
            }
            log.info("待处理图片 {} 张（查询到 {} 张，已处理 {} 张），并发 {}",
                    pending.size(), all.size(), all.size() - pending.size(), concurrency);
            if (pending.isEmpty()) {
                log.info("========== AI 图片重命名任务结束：没有待处理的图片 ==========");
                return;
            }

            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
            CountDownLatch latch = new CountDownLatch(pending.size());
            for (Picture picture : pending) {
                pool.submit(() -> {
                    try {
                        if (renameOne(picture)) {
                            okCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } finally {
                        int done = finishedCount.incrementAndGet();
                        if (done % 100 == 0) {
                            log.info("AI 重命名进度：{}/{}，成功 {}，失败 {}",
                                    done, pending.size(), okCount.get(), failCount.get());
                        }
                        latch.countDown();
                    }
                });
            }
            latch.await();
            pool.shutdown();
            log.info("========== AI 图片重命名任务结束：成功 {}，失败 {}，耗时 {} 分钟 ==========",
                    okCount.get(), failCount.get(), (System.currentTimeMillis() - startTime) / 60000);
        } catch (Throwable t) {
            log.error("AI 图片重命名任务异常终止", t);
        }
    }

    /**
     * 处理一张图片：下载 -> 视觉模型 -> 更新名称/标签 -> 记完成
     */
    private boolean renameOne(Picture picture) {
        try {
            byte[] imageBytes = downloadImage(picture);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("图片下载失败，跳过：id={}, url={}", picture.getId(), picture.getUrl());
                return false;
            }
            AliYunAiApi.ImageDescription description =
                    aliYunAiApi.describeImage(imageBytes, guessMimeType(picture));
            if (description == null || StrUtil.isBlank(description.getName())) {
                log.warn("AI 未返回有效描述，跳过：id={}, name={}", picture.getId(), picture.getName());
                return false;
            }
            String name = cleanName(description.getName());
            List<String> tags = cleanTags(description.getTags());
            // tags 在库里存的是 JSON 数组字符串，和上传逻辑保持一致
            String tagsJson = CollUtil.isEmpty(tags) ? null : JSONUtil.toJsonStr(tags);
            boolean updated = pictureRepository.lambdaUpdate()
                    .eq(Picture::getId, picture.getId())
                    .set(Picture::getName, name)
                    .set(StrUtil.isNotBlank(tagsJson), Picture::getTags, tagsJson)
                    .update();
            if (!updated) {
                log.warn("更新图片失败：id={}", picture.getId());
                return false;
            }
            stringRedisTemplate.opsForSet().add(DONE_KEY, String.valueOf(picture.getId()));
            return true;
        } catch (Exception e) {
            log.warn("AI 重命名失败：id={}, name={}", picture.getId(), picture.getName(), e);
            return false;
        }
    }

    /**
     * 优先下载原图；原图太大或下载失败就用缩略图
     */
    private byte[] downloadImage(Picture picture) {
        byte[] original = download(picture.getUrl());
        if (original != null && original.length <= MAX_IMAGE_BYTES) {
            return original;
        }
        byte[] thumbnail = download(picture.getThumbnailUrl());
        return thumbnail != null ? thumbnail : original;
    }

    private byte[] download(String url) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        try (HttpResponse response = HttpRequest.get(url)
                .header("Referer", referer)
                .timeout(30000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("下载图片失败：status={}, url={}", response.getStatus(), url);
                return null;
            }
            return response.bodyBytes();
        } catch (Exception e) {
            log.warn("下载图片异常：url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 按图片格式拼 MIME，模型靠它识别 base64 内容
     */
    private String guessMimeType(Picture picture) {
        String format = StrUtil.blankToDefault(picture.getPicFormat(), "jpeg").toLowerCase();
        if ("jpg".equals(format)) {
            return "image/jpeg";
        }
        return "image/" + format;
    }

    private String cleanName(String raw) {
        String name = StrUtil.trim(raw)
                .replaceAll("[\\r\\n\\t]", "")
                .replace("\"", "")
                .trim();
        name = StrUtil.removePrefix(name, "名称：");
        name = StrUtil.removePrefix(name, "名称:");
        if (name.length() > NAME_MAX_LENGTH) {
            name = name.substring(0, NAME_MAX_LENGTH);
        }
        return name;
    }

    private List<String> cleanTags(List<String> rawTags) {
        if (CollUtil.isEmpty(rawTags)) {
            return new ArrayList<>();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String raw : rawTags) {
            String tag = StrUtil.trim(raw);
            if (StrUtil.isBlank(tag)) {
                continue;
            }
            // 标签最终按 JSON 数组存，但去掉逗号，避免以后有人按逗号拆
            tag = tag.replace(",", "").replace("，", "");
            if (tag.length() > TAG_MAX_LENGTH) {
                tag = tag.substring(0, TAG_MAX_LENGTH);
            }
            tags.add(tag);
            if (tags.size() >= TAG_MAX_COUNT) {
                break;
            }
        }
        return new ArrayList<>(tags);
    }

    /**
     * 易烊千玺的图一律不动：分类、名称、标签任意一处命中都跳过
     */
    private boolean isJacksonYee(Picture picture) {
        return "易烊千玺".equals(picture.getCategory())
                || (picture.getName() != null && picture.getName().contains("易烊千玺"))
                || (picture.getTags() != null && picture.getTags().contains("易烊千玺"));
    }
}
