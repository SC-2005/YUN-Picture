package com.songcan.yupicture.application.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.songcan.yupicture.domain.picture.entity.Picture;
import com.songcan.yupicture.domain.picture.repository.PictureRepository;
import com.songcan.yupicture.domain.picture.service.PictureDomainService;
import com.songcan.yupicture.domain.user.constant.UserConstant;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.domain.user.repository.UserRepository;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.interfaces.dto.picture.PictureUploadByBatchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次性补图任务：把每个分类补到指定数量。
 * <p>
 * 默认不启用，只有配置了 {@code picture.fill.enabled=true} 才会在启动后跑一个后台线程。
 * 生产上通过临时 systemd drop-in 打开，补完删掉即可，不影响正常上传/浏览。
 * <p>
 * 任务本身是幂等的：启动时先统计每个分类已有多少张，只补差额；
 * 进程重启后会接着补，不会从头再抓一遍（Redis 里的 seen 集合也会跳过已经抓过的图）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "picture.fill", name = "enabled", havingValue = "true")
public class PictureFillRunner implements ApplicationRunner {

    /**
     * 每个分类的目标数量
     */
    @Value("${picture.fill.target:500}")
    private int target;

    /**
     * 每批最多抓多少张（后端 uploadPictureByBatch 的上限就是 30）
     */
    private static final int BATCH_SIZE = 30;

    /**
     * 单个分类最多调用多少次批量抓取，防止某个关键词彻底失效时死循环
     */
    private static final int MAX_BATCHES_PER_CATEGORY = 60;

    /**
     * 两次批量抓取之间的间隔，给 360 和图片源留点喘息时间
     */
    private static final long SLEEP_MILLIS = 800L;

    /**
     * 补图计划。顺序就是执行顺序，易烊千玺放最前面。
     */
    private static final List<FillPlan> PLANS = Arrays.asList(
            new FillPlan("易烊千玺",
                    Arrays.asList("易烊千玺", "易烊千玺高清壁纸", "易烊千玺头像",
                            "易烊千玺写真", "易烊千玺舞台", "易烊千玺杂志"),
                    Arrays.asList("明星", "高清")),
            new FillPlan("壁纸",
                    Arrays.asList("高清壁纸", "手机壁纸", "电脑壁纸",
                            "风景壁纸", "动漫壁纸", "简约壁纸"),
                    Arrays.asList("壁纸", "高清")),
            new FillPlan("二次元",
                    Arrays.asList("二次元", "二次元壁纸", "二次元头像",
                            "动漫少女", "二次元插画", "二次元人物"),
                    Arrays.asList("二次元", "可爱")),
            new FillPlan("表情包",
                    Arrays.asList("表情包", "搞笑表情包", "可爱表情包",
                            "聊天表情包", "猫猫表情包", "熊猫头表情包"),
                    Arrays.asList("表情包", "搞笑")),
            new FillPlan("海报",
                    Arrays.asList("海报设计", "促销海报", "节日海报",
                            "创意海报", "电影海报", "招聘海报"),
                    Arrays.asList("海报", "创意")),
            new FillPlan("风景",
                    Arrays.asList("风景", "自然风景", "山水风景",
                            "海边风景", "日落风景", "星空风景"),
                    Arrays.asList("风景", "治愈")),
            new FillPlan("二次元壁纸",
                    Arrays.asList("二次元壁纸", "动漫壁纸", "二次元壁纸高清",
                            "动漫壁纸手机", "二次元壁纸电脑", "动漫壁纸4k"),
                    Arrays.asList("二次元", "壁纸")),
            new FillPlan("摄影",
                    Arrays.asList("摄影", "人像摄影", "街头摄影",
                            "风光摄影", "黑白摄影", "纪实摄影"),
                    Arrays.asList("摄影", "高清")),
            new FillPlan("电商",
                    Arrays.asList("电商", "商品摄影", "产品图",
                            "淘宝商品", "服装模特", "电商海报"),
                    Arrays.asList("电商", "商务")),
            new FillPlan("模板",
                    Arrays.asList("设计模板", "简历模板", "PPT模板",
                            "海报模板", "证书模板", "邀请函模板"),
                    Arrays.asList("模板", "简约"))
    );

    /**
     * 用户追加的 500 张动漫图，放在独立的「动漫」分类里（不再混进二次元）。
     * 目标写绝对值而不是「再抓 500 张」，这样进程重启后接着补，不会重复加。
     */
    private static final FillPlan EXTRA_ANIME_PLAN = new FillPlan("动漫",
            Arrays.asList("动漫", "动漫人物", "动漫少女", "动漫男头",
                    "动漫情侣", "动漫头像"),
            Arrays.asList("动漫", "二次元"), 500);

    /**
     * 用户追加的 500 张「随意」图：除易烊千玺、二次元、动漫外的 8 个分类每类加 62/63 张，
     * 合计正好 500。同样是绝对目标，重启幂等。
     */
    private static final List<FillPlan> RANDOM_PLANS = Arrays.asList(
            new FillPlan("壁纸", Arrays.asList("汽车壁纸", "花卉壁纸", "星空壁纸",
                    "简约壁纸", "可爱壁纸", "游戏壁纸"),
                    Arrays.asList("壁纸", "高清"), 562),
            new FillPlan("表情包", Arrays.asList("沙雕表情包", "萌宠表情包", "打工人表情包",
                    "搞笑截图", "猫咪表情包", "日常表情包"),
                    Arrays.asList("表情包", "搞笑"), 562),
            new FillPlan("海报", Arrays.asList("招聘海报", "美食海报", "活动海报",
                    "新品海报", "音乐海报", "展览海报"),
                    Arrays.asList("海报", "创意"), 562),
            new FillPlan("风景", Arrays.asList("雪山风景", "草原风景", "沙漠风景",
                    "樱花风景", "古镇风景", "瀑布风景"),
                    Arrays.asList("风景", "治愈"), 562),
            new FillPlan("二次元壁纸", Arrays.asList("原神壁纸", "初音未来壁纸", "动漫少女壁纸",
                    "二次元手机壁纸", "动漫场景壁纸", "二次元电脑壁纸"),
                    Arrays.asList("二次元", "壁纸"), 563),
            new FillPlan("摄影", Arrays.asList("街头摄影", "建筑摄影", "美食摄影",
                    "夜景摄影", "黑白摄影", "旅行摄影"),
                    Arrays.asList("摄影", "高清"), 563),
            new FillPlan("电商", Arrays.asList("美妆产品", "数码产品", "服装商品",
                    "家居产品", "食品包装", "产品展示"),
                    Arrays.asList("电商", "商务"), 563),
            new FillPlan("模板", Arrays.asList("简历模板", "邀请函模板", "证书模板",
                    "名片模板", "宣传单模板", "PPT模板"),
                    Arrays.asList("模板", "简约"), 563)
    );

    /**
     * 旧分类 -> 新分类。分类从 24 个减到 10 个后，旧数据要归到最接近的保留分类，
     * 否则这些图片在首页分类 tab 里会“消失”（只能在「全部」里看到）。
     */
    private static final Map<String, String> CATEGORY_REMAP = new LinkedHashMap<String, String>() {{
        put("素材", "模板");
        put("背景", "壁纸");
        put("人物", "摄影");
        put("动物", "摄影");
        put("美食", "摄影");
        put("建筑", "摄影");
        put("科技", "摄影");
        put("游戏", "二次元壁纸");
        // 注意：「动漫」已经是独立分类，这里绝对不能再归并到二次元壁纸，
        // 否则每次重启都会把整个动漫分类搬走
        put("国风", "二次元壁纸");
        // 上一轮已经归到「插画」的旧数据，这一轮全部挪到替换它的「二次元壁纸」
        put("插画", "二次元壁纸");
        put("节日", "海报");
        put("运动", "摄影");
        put("简约", "模板");
        put("渐变", "模板");
        put("其他", "摄影");
    }};

    @Resource
    private PictureDomainService pictureDomainService;

    @Resource
    private PictureRepository pictureRepository;

    @Resource
    private UserRepository userRepository;

    private final AtomicBoolean started = new AtomicBoolean(false);

    @Override
    public void run(ApplicationArguments args) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::fillAll, "picture-fill");
        thread.setDaemon(false);
        thread.start();
    }

    private void fillAll() {
        long startTime = System.currentTimeMillis();
        try {
            log.info("========== 批量补图任务启动，目标每类 {} 张 ==========", target);
            User admin = findAdmin();
            if (admin == null) {
                log.error("批量补图终止：数据库里找不到管理员账号");
                return;
            }
            log.info("批量补图使用管理员账号：id={}, account={}", admin.getId(), admin.getUserAccount());
            // 头像分类涉及版权风险，整体删除（数据库记录 + COS 原图/缩略图）
            deletePicturesByCategory("头像", admin);
            reclassifyExistingPictures();
            // 旧归并表曾把「动漫」错并到「二次元壁纸」，把被搬错的图先搬回来
            repairMisclassifiedAnimePictures();
            // 上一版追加任务把动漫图抓进了二次元，名字从「二次元501」开始，先挪到独立的动漫分类
            moveExtraAnimeToAnimeCategory();
            for (FillPlan plan : PLANS) {
                fillCategory(plan, admin);
            }
            // 用户追加：二次元再补 500 张动漫（500 -> 1000）
            fillCategory(EXTRA_ANIME_PLAN, admin);
            // 用户追加：再抓 500 张杂图，分散到除易烊千玺、二次元、动漫外的 8 个分类
            for (FillPlan plan : RANDOM_PLANS) {
                fillCategory(plan, admin);
            }
            log.info("========== 批量补图任务结束，耗时 {} 分钟 ==========",
                    (System.currentTimeMillis() - startTime) / 60000);
        } catch (Throwable t) {
            log.error("批量补图任务异常终止", t);
        }
    }

    /**
     * 取一个管理员账号作为上传人：批量抓取会做审核字段填充，管理员上传直接审核通过。
     */
    private User findAdmin() {
        return userRepository.lambdaQuery()
                .eq(User::getUserRole, UserConstant.ADMIN_ROLE)
                .orderByAsc(User::getId)
                .last("limit 1")
                .one();
    }

    /**
     * 删除某个分类下的所有图片
     * <p>
     * 走 domain service 的 deletePicture，会同时删掉数据库记录和 COS 上的原图/缩略图；
     * 直接 DELETE SQL 只会删库，文件会留在对象存储里继续占空间。
     */
    private void deletePicturesByCategory(String category, User admin) {
        List<Picture> pictures = pictureRepository.lambdaQuery()
                .eq(Picture::getCategory, category)
                .list();
        if (pictures.isEmpty()) {
            log.info("分类「{}」没有需要删除的图片", category);
            return;
        }
        log.info("开始删除分类「{}」的图片，共 {} 张", category, pictures.size());
        int deleted = 0;
        int failed = 0;
        for (Picture picture : pictures) {
            try {
                pictureDomainService.deletePicture(picture.getId(), admin);
                deleted++;
            } catch (Exception e) {
                failed++;
                log.error("删除图片失败：id={}, name={}", picture.getId(), picture.getName(), e);
            }
        }
        log.info("分类「{}」删除完成：成功 {} 张，失败 {} 张", category, deleted, failed);
    }

    /**
     * 整理旧数据：
     * 1）名字或标签里带「易烊千玺」的图片，全部归到易烊千玺分类；
     * 2）已经被删掉的旧分类，归到最接近的保留分类。
     */
    private void reclassifyExistingPictures() {
        try {
            UpdateWrapper<Picture> jacksonWrapper = new UpdateWrapper<>();
            jacksonWrapper.and(w -> w.like("name", "易烊千玺").or().like("tags", "易烊千玺"))
                    .set("category", "易烊千玺");
            int moved = pictureRepository.getBaseMapper().update(null, jacksonWrapper);
            log.info("易烊千玺图片归拢完成，更新 {} 张", moved);
        } catch (Exception e) {
            log.error("易烊千玺图片归拢失败，继续补图", e);
        }

        for (Map.Entry<String, String> entry : CATEGORY_REMAP.entrySet()) {
            try {
                UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("category", entry.getKey())
                        .set("category", entry.getValue());
                int moved = pictureRepository.getBaseMapper().update(null, updateWrapper);
                if (moved > 0) {
                    log.info("旧分类归并：{} -> {}，更新 {} 张", entry.getKey(), entry.getValue(), moved);
                }
            } catch (Exception e) {
                log.error("旧分类归并失败：{} -> {}", entry.getKey(), entry.getValue(), e);
            }
        }
    }

    /**
     * 把被旧归并规则误搬到「二次元壁纸」的动漫图搬回「动漫」。
     * <p>
     * 旧归并表里有「动漫 -> 二次元壁纸」，动漫独立成类后这一行没删，结果上次重启
     * 把整个动漫分类（约 500 张）搬进了二次元壁纸。这里按名字识别并搬回来：
     * 名字以「动漫」开头的（追加任务抓的图），以及「二次元501」这类追加编号的图
     * （上一版从二次元迁移过来的）。二次元壁纸自己的图名字是「二次元壁纸xxx」，
     * 后缀不是纯数字，不会被误伤。
     */
    private void repairMisclassifiedAnimePictures() {
        try {
            List<Picture> pictures = pictureRepository.lambdaQuery()
                    .eq(Picture::getCategory, "二次元壁纸")
                    .list();
            List<Long> ids = new ArrayList<>();
            for (Picture picture : pictures) {
                String name = picture.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (name.startsWith("动漫")) {
                    ids.add(picture.getId());
                    continue;
                }
                if (name.startsWith("二次元")) {
                    try {
                        if (Integer.parseInt(name.substring("二次元".length())) > 500) {
                            ids.add(picture.getId());
                        }
                    } catch (NumberFormatException ignored) {
                        // 「二次元壁纸1」这类正常名字，后缀不是数字，忽略
                    }
                }
            }
            if (ids.isEmpty()) {
                log.info("没有需要从「二次元壁纸」搬回「动漫」的图片");
                return;
            }
            UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<>();
            updateWrapper.in("id", ids).set("category", "动漫");
            int moved = pictureRepository.getBaseMapper().update(null, updateWrapper);
            log.info("动漫图修复完成：二次元壁纸 -> 动漫，更新 {} 张", moved);
        } catch (Exception e) {
            log.error("动漫图修复失败，继续补图", e);
        }
    }

    /**
     * 把上一版追加任务抓进「二次元」的动漫图挪到独立的「动漫」分类。
     * <p>
     * 主任务给二次元编号到 500（二次元1 ~ 二次元500），追加任务从「二次元501」开始编号，
     * 所以按名字后缀 &gt; 500 就能准确识别这批图；用户自己上传、名字不是这个格式的一律不动。
     */
    private void moveExtraAnimeToAnimeCategory() {
        try {
            List<Picture> pictures = pictureRepository.lambdaQuery()
                    .eq(Picture::getCategory, "二次元")
                    .likeRight(Picture::getName, "二次元")
                    .list();
            List<Long> ids = new ArrayList<>();
            for (Picture picture : pictures) {
                String name = picture.getName();
                if (name == null || name.length() <= "二次元".length()) {
                    continue;
                }
                try {
                    if (Integer.parseInt(name.substring("二次元".length())) > 500) {
                        ids.add(picture.getId());
                    }
                } catch (NumberFormatException ignored) {
                    // 名字不是「二次元 + 数字」格式，不属于追加任务，忽略
                }
            }
            if (ids.isEmpty()) {
                log.info("没有需要从「二次元」挪到「动漫」的追加图片");
                return;
            }
            UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<>();
            updateWrapper.in("id", ids).set("category", "动漫");
            int moved = pictureRepository.getBaseMapper().update(null, updateWrapper);
            log.info("追加动漫图归拢完成：{} -> 动漫，更新 {} 张", ids.size(), moved);
        } catch (Exception e) {
            log.error("追加动漫图归拢失败，继续补图", e);
        }
    }

    private void fillCategory(FillPlan plan, User admin) {
        // 主计划用全局目标；追加计划自带绝对目标（例如动漫补到 500）
        int goal = plan.goal > 0 ? plan.goal : target;
        long existing = countByCategory(plan.category);
        int uploaded = 0;
        int attempt = 0;
        int termIndex = 0;
        int emptyRounds = 0;
        log.info("[{}] 已有 {} 张，目标 {} 张", plan.category, existing, goal);

        while (existing + uploaded < goal && attempt < MAX_BATCHES_PER_CATEGORY) {
            String searchText = plan.searchTexts.get(termIndex % plan.searchTexts.size());
            int need = (int) Math.min(BATCH_SIZE, goal - existing - uploaded);

            PictureUploadByBatchRequest request = new PictureUploadByBatchRequest();
            request.setSearchText(searchText);
            request.setNamePrefix(plan.category);
            request.setStartIndex((int) (existing + uploaded));
            request.setCount(need);
            request.setCategory(plan.category);
            request.setTags(plan.tags);

            attempt++;
            try {
                Integer count = pictureDomainService.uploadPictureByBatch(request, admin);
                if (count != null && count > 0) {
                    uploaded += count;
                    emptyRounds = 0;
                    log.info("[{}] 「{}」本批 +{}，累计 {}/{}", plan.category, searchText,
                            count, existing + uploaded, goal);
                } else {
                    emptyRounds++;
                    log.info("[{}] 「{}」本批没有抓到新图", plan.category, searchText);
                }
            } catch (BusinessException e) {
                emptyRounds++;
                log.warn("[{}] 「{}」本批没有新图：{}", plan.category, searchText, e.getMessage());
            } catch (Exception e) {
                emptyRounds++;
                log.error("[{}] 「{}」抓取异常", plan.category, searchText, e);
            }

            termIndex++;
            // 所有关键词都轮了两遍还是零新增，说明这一类能抓的已经抓完，提前收工
            if (emptyRounds >= plan.searchTexts.size() * 2) {
                log.warn("[{}] 连续 {} 批没有新图，提前结束，本次补了 {} 张",
                        plan.category, emptyRounds, uploaded);
                break;
            }
            sleep();
        }

        long total = existing + uploaded;
        if (total < goal) {
            log.warn("[{}] 未达到目标：已有 {} + 新增 {} = {} < {}", plan.category, existing, uploaded, total, goal);
        } else {
            log.info("[{}] 已完成：{} 张", plan.category, total);
        }
    }

    private long countByCategory(String category) {
        Long count = pictureRepository.lambdaQuery()
                .eq(Picture::getCategory, category)
                .count();
        return count == null ? 0 : count;
    }

    private void sleep() {
        try {
            Thread.sleep(SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("批量补图线程被中断", e);
        }
    }

    /**
     * 一个分类的补图计划：分类名、搜索词列表、标签、目标数量。
     */
    private static class FillPlan {
        private final String category;
        private final List<String> searchTexts;
        private final List<String> tags;
        /**
         * 绝对目标数量；<= 0 表示用全局配置 picture.fill.target
         */
        private final int goal;

        private FillPlan(String category, List<String> searchTexts, List<String> tags) {
            this(category, searchTexts, tags, 0);
        }

        private FillPlan(String category, List<String> searchTexts, List<String> tags, int goal) {
            this.category = category;
            this.searchTexts = new ArrayList<>(searchTexts);
            this.tags = new ArrayList<>(tags);
            this.goal = goal;
        }
    }
}
