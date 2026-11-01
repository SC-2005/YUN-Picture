package com.songcan.yupicture.domain.picture.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.songcan.yupicture.application.service.SpaceApplicationService;
import com.songcan.yupicture.domain.picture.entity.Picture;
import com.songcan.yupicture.domain.picture.repository.PictureRepository;
import com.songcan.yupicture.domain.picture.service.PictureDomainService;
import com.songcan.yupicture.domain.picture.valueobject.PictureReviewStatusEnum;
import com.songcan.yupicture.domain.space.entity.Space;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.infrastructure.api.CosManager;
import com.songcan.yupicture.infrastructure.api.aliyunai.AliYunAiApi;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.songcan.yupicture.infrastructure.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import com.songcan.yupicture.infrastructure.manager.upload.FilePictureUpload;
import com.songcan.yupicture.infrastructure.manager.upload.PictureUploadTemplate;
import com.songcan.yupicture.infrastructure.manager.upload.UrlPictureUpload;
import com.songcan.yupicture.infrastructure.manager.upload.model.dto.file.UploadPictureResult;
import com.songcan.yupicture.infrastructure.utils.ColorSimilarUtils;
import com.songcan.yupicture.interfaces.dto.picture.*;
import com.songcan.yupicture.interfaces.vo.picture.PictureVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author 34451
 * @description 针对表【picture(图片)】的数据库操作Service实现
 * @createDate 2026-09-09 19:15:40
 */
@Slf4j
@Service
public class PictureDomainServiceImpl implements PictureDomainService {

    @Resource
    private PictureRepository pictureRepository;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private FilePictureUpload filePictureUpload;

    @Resource
    private UrlPictureUpload urlPictureUpload;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private AliYunAiApi aliYunAiApi;

    @Resource
    private CosManager cosManager;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 批量抓取：已抓过的图片标识，跨关键词去重 */
    private static final String BATCH_SEEN_KEY = "picture:batch:seen";

    /** 批量抓取：每个搜索词下次从第几个结果开始 */
    private static final String BATCH_CURSOR_KEY_PREFIX = "picture:batch:cursor:";

    /** 360 图片搜索接口 */
    private static final String BATCH_SEARCH_API = "https://image.so.com/j";

    /** 单次批量抓取最多翻几页，避免接口异常时无限重试 */
    private static final int BATCH_MAX_PAGES = 3;

    /** 抓取偏移量上限：再往后结果质量会下降，到顶就回到第一页 */
    private static final int BATCH_MAX_OFFSET = 600;

    private static final String BATCH_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /**
     * 允许排序的字段白名单
     * <p>
     * sortField 会被拼进 ORDER BY，不能直接信任前端传来的值（SQL 注入面）。
     * 同时它也能挡住 "picScale" 这类真实存在、但排序了也没意义的字段。
     */
    private static final Set<String> SORT_FIELD_WHITELIST = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("createTime", "editTime", "updateTime", "picSize", "id")));

    /**
     * 自动分类的关键词规则表：搜索词包含 key 就归到 value 这个分类
     * <p>
     * 用 LinkedHashMap 保证「先定义的规则先命中」——比如「简历模板」同时含「简历」和「模板」，
     * 谁写在前面就按谁算，规则顺序是有意义的，不要换成 HashMap。
     */
    private static final Map<String, String> CATEGORY_KEYWORD_RULES = new LinkedHashMap<String, String>() {{
        // 分类现在 11 个：易烊千玺、壁纸、二次元、动漫、表情包、海报、风景、二次元壁纸、摄影、电商、模板。
        // 越具体的规则越靠前：「手机壁纸」要归到壁纸，不能先被「手机」拦去摄影；
        // 「易烊千玺壁纸」也必须先命中易烊千玺，不能被壁纸抢走。
        put("易烊千玺", "易烊千玺");
        put("千玺", "易烊千玺");
        put("jackson yee", "易烊千玺");
        put("四字弟弟", "易烊千玺");
        put("表情", "表情包");
        put("emoji", "表情包");
        put("meme", "表情包");
        put("搞笑", "表情包");
        put("逗图", "表情包");
        // 「头像」分类已因版权风险删除，不再产出这个值；纯「头像」搜索会落到兜底的摄影
        put("壁纸", "壁纸");
        put("wallpaper", "壁纸");
        put("背景", "壁纸");
        put("background", "壁纸");
        put("桌面", "壁纸");
        put("电商", "电商");
        put("商品", "电商");
        put("产品图", "电商");
        put("购物", "电商");
        put("模特", "电商");
        put("淘宝", "电商");
        put("京东", "电商");
        put("模板", "模板");
        put("template", "模板");
        put("ppt", "模板");
        put("简历", "模板");
        put("素材", "模板");
        put("icon", "模板");
        put("图标", "模板");
        put("简约", "模板");
        put("极简", "模板");
        put("minimal", "模板");
        put("渐变", "模板");
        put("gradient", "模板");
        put("海报", "海报");
        put("poster", "海报");
        put("banner", "海报");
        put("宣传", "海报");
        put("广告", "海报");
        put("节日", "海报");
        put("春节", "海报");
        put("圣诞", "海报");
        put("新年", "海报");
        put("中秋", "海报");
        put("国庆", "海报");
        put("festival", "海报");
        put("christmas", "海报");
        // 「二次元壁纸」是独立分类，规则必须放在「二次元」「壁纸」前面，
        // 否则「二次元壁纸」会先被「二次元」拦住。
        put("二次元壁纸", "二次元壁纸");
        put("动漫壁纸", "二次元壁纸");
        // 「动漫」已从二次元独立成单独分类；二次元只保留泛二次元/Cartoon 类关键词
        put("二次元", "二次元");
        put("cartoon", "二次元");
        put("卡通", "二次元");
        put("动漫", "动漫");
        put("动画", "动漫");
        put("漫画", "动漫");
        put("anime", "动漫");
        // 「插画」分类已取消，不再产出这个值；手绘/国风/游戏等词按兜底归到摄影
        put("风景", "风景");
        put("山水", "风景");
        put("自然", "风景");
        put("森林", "风景");
        put("海洋", "风景");
        put("海边", "风景");
        put("天空", "风景");
        put("landscape", "风景");
        put("scenery", "风景");
        put("nature", "风景");
        put("旅行", "风景");
        put("旅游", "风景");
        put("摄影", "摄影");
        put("照片", "摄影");
        put("photo", "摄影");
        put("photography", "摄影");
        put("人物", "摄影");
        put("人像", "摄影");
        put("美女", "摄影");
        put("帅哥", "摄影");
        put("女孩", "摄影");
        put("男孩", "摄影");
        put("portrait", "摄影");
        put("动物", "摄影");
        put("宠物", "摄影");
        put("萌宠", "摄影");
        put("猫咪", "摄影");
        put("狗狗", "摄影");
        put("animal", "摄影");
        put("pet", "摄影");
        put("cat", "摄影");
        put("dog", "摄影");
        put("美食", "摄影");
        put("食物", "摄影");
        put("蛋糕", "摄影");
        put("咖啡", "摄影");
        put("甜品", "摄影");
        put("food", "摄影");
        put("cake", "摄影");
        put("coffee", "摄影");
        put("dessert", "摄影");
        put("建筑", "摄影");
        put("城市", "摄影");
        put("房子", "摄影");
        put("室内", "摄影");
        put("architecture", "摄影");
        put("building", "摄影");
        put("city", "摄影");
        put("interior", "摄影");
        put("科技", "摄影");
        put("数码", "摄影");
        put("电脑", "摄影");
        put("手机", "摄影");
        put("未来", "摄影");
        put("tech", "摄影");
        put("technology", "摄影");
        put("digital", "摄影");
        put("运动", "摄影");
        put("健身", "摄影");
        put("篮球", "摄影");
        put("足球", "摄影");
        put("跑步", "摄影");
        put("sport", "摄影");
        put("fitness", "摄影");
        put("明星", "摄影");
    }};

    /**
     * 转义 LIKE 的模糊匹配通配符
     * <p>
     * MySQL 的 LIKE 默认以 \ 作为转义符，所以把 \ % _ 各自转义后，用户输入的 % 就只是普通字符。
     * 不转义的话，前端传 ?tags=%25 会让匹配模式变成 %"%"%、把所有行都匹配出来，
     * 筛选条件等于被绕过。
     */
    private static String escapeLike(String value) {
        if (StrUtil.isEmpty(value)) {
            return value;
        }
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 修改图片
     *
     * @param inputSource
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {
        //校验参数
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        //判断是上传还是更新
        Long pictureId = pictureUploadRequest == null ? null : pictureUploadRequest.getId();
        Picture oldPicture = null;
        //如果是更新,先把原图查出来：空间一致性校验、额度按差值算、清理旧文件，三处都要用它
        if (pictureId != null) {
            oldPicture = pictureRepository.getById(pictureId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 权限交给 @SaSpaceCheckPermission(picture:upload) 统一校验：
            // 团队空间的编辑者也能替换别人上传的图片，这里不能再限制「只有原上传人/管理员」，
            // 否则编辑者一换图就被判「无权限」
        }
        //归一化空间 id：没传 spaceid 则复用原有图片的 spaceid；传了必须和原图一致（不允许把图挪到别的空间）
        Long spaceId = pictureUploadRequest.getSpaceId();
        if (oldPicture != null) {
            if (spaceId == null) {
                spaceId = oldPicture.getSpaceId();
            } else if (ObjUtil.notEqual(spaceId, oldPicture.getSpaceId())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间 id 不一致");
            }
        }
        // 是否是「同一张图换个文件」：是的话条数不变，大小按差值算
        boolean isUpdate = oldPicture != null && spaceId != null && spaceId.equals(oldPicture.getSpaceId());
        long oldPicSize = isUpdate && oldPicture.getPicSize() != null ? oldPicture.getPicSize() : 0L;
        // 校验空间是否存在、有没有权限、额度够不够
        if (spaceId != null) {
            Space space = spaceApplicationService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            // 上传权限交给 @SaSpaceCheckPermission(picture:upload) 统一校验：
            // 私有空间只有创建人/管理员有该权限，团队空间按成员角色下发（编辑者、管理员可以上传）。
            // 这里不能再判断 space.getUserId()，否则团队空间的编辑者会被当成「没有空间权限」
            // 校验额度
            // 条数：更新是同一张图换文件，不占新条数，所以满员的空间也应该允许换图
            if (!isUpdate && space.getTotalCount() >= space.getMaxCount()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间条数不足");
            }
            // 大小：新文件多大要传完才知道，这里只能做保守判断 ——
            // 把这张图占的旧空间先还回去仍然放不下，就直接拒绝；否则先放行，传完再按实际大小结算。
            // 不在这里精确判断（totalSize - 旧 + 新 > maxSize）是因为要精确就得先上传，
            // 而上传是往对象存储写文件，被拒了就留下一个没人引用的垃圾文件
            if (space.getTotalSize() - oldPicSize >= space.getMaxSize()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间大小不足");
            }
        }
        //上传图片
        // 上传图片，得到图片信息,按照空间划分目录
        // 按照用户 id 划分目录 => 按照空间划分目录
        String uploadPathPrefix;
        if (spaceId == null) {
            //公共图库
            uploadPathPrefix = String.format("public/%s", loginUser.getId());
        } else {
            //空间图库
            uploadPathPrefix = String.format("space/%s", spaceId);
        }
        // 根据 inputSource 的类型区分上传方式
        PictureUploadTemplate pictureUploadTemplate = filePictureUpload;
        if (inputSource instanceof String) {
            pictureUploadTemplate = urlPictureUpload;
        }
        UploadPictureResult uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        // 构造要入库的图片信息
        Picture picture = new Picture();
        picture.setSpaceId(spaceId);//空间id
        picture.setUrl(uploadPictureResult.getUrl());
        // 缩略图缺失时退回压缩图地址。不能留 null：MyBatis-Plus 的 updateById 默认跳过 null 字段，
        // 更新场景下旧缩略图紧接着会被 clearPictureFile 删掉，留 null 就会退回旧值、变成一条死链
        picture.setThumbnailUrl(StrUtil.blankToDefault(
                uploadPictureResult.getThumbnailUrl(), uploadPictureResult.getUrl()));
        // 支持外层传递图片名称
        String picName = uploadPictureResult.getPicName();
        if (pictureUploadRequest != null && StrUtil.isNotBlank(pictureUploadRequest.getPicName())) {
            picName = pictureUploadRequest.getPicName();
        }
        picture.setName(picName);
        // 支持外层传递分类（批量抓取时由外层指定或自动推断）；普通上传不传，保持 null
        if (pictureUploadRequest != null && StrUtil.isNotBlank(pictureUploadRequest.getCategory())) {
            picture.setCategory(pictureUploadRequest.getCategory());
        }
        // 支持外层传递标签（批量抓取时整批共用）；普通上传不传，保持 null。
        // 更新场景下 null 会被 MyBatis-Plus 跳过，不会把原标签清空
        if (pictureUploadRequest != null) {
            List<String> tags = normalizeTags(pictureUploadRequest.getTags());
            if (CollUtil.isNotEmpty(tags)) {
                picture.setTags(JSONUtil.toJsonStr(tags));
            }
        }
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        picture.setPicColor(uploadPictureResult.getPicColor());
        // 更新时沿用原作者的归属：管理员有权改别人的图，但归属不该被改写成管理员自己
        picture.setUserId(oldPicture != null ? oldPicture.getUserId() : loginUser.getId());
        //补充审核参数
        this.fillReviewParams(picture, loginUser);
        //操作数据库
        //如果picturId不为空的话,说明是更新操作
        if (pictureId != null) {
            picture.setId(pictureId);
            picture.setUpdateTime(new Date());
        }
        // 开启事务：图片入库和额度更新要么一起成功、要么一起失败
        Long finalSpaceId = spaceId;
        boolean finalIsUpdate = isUpdate;
        long finalOldPicSize = oldPicSize;
        long newPicSize = picture.getPicSize() == null ? 0L : picture.getPicSize();
        transactionTemplate.execute(status -> {
            boolean result = pictureRepository.saveOrUpdate(picture);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
            if (finalSpaceId != null) {
                // 总大小按「新文件 - 旧文件」的差值算：更新时旧图马上就会被删掉，它占的额度要先还回去。
                // 直接 + 新图大小的话，同一张图每换一次文件空间就多占一份，越更新越满
                LambdaUpdateWrapper<Space> spaceUpdateWrapper = new LambdaUpdateWrapper<>();
                spaceUpdateWrapper.eq(Space::getId, finalSpaceId)
                        .setSql("totalSize = totalSize - " + finalOldPicSize + " + " + newPicSize);
                // 条数只有新上传才 +1；更新是同一张图换文件，条数不变
                if (!finalIsUpdate) {
                    spaceUpdateWrapper.setSql("totalCount = totalCount + 1");
                }
                boolean update = spaceApplicationService.update(spaceUpdateWrapper);
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return picture;
        });
        // 更新操作要清掉旧的图片/缩略图文件，放在事务提交之后：
        // 数据已经指向新文件了，这里删失败不影响正确性（最多留个没人引用的垃圾文件，
        // 真出问题靠后续的定时任务扫描补偿，代价比把删文件塞进事务小得多）
        if (pictureId != null && oldPicture != null) {
            // clearPictureFile 内部会判断这个 url 有没有被别的记录引用，被引用则不删
            this.clearPictureFile(oldPicture);
        }
        return PictureVO.objToVo(picture);
    }

    /**
     * 获取查询对象
     *
     * @param pictureQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Integer picWidthMax = pictureQueryRequest.getPicWidthMax();
        Integer picHeightMax = pictureQueryRequest.getPicHeightMax();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        Long spaceId = pictureQueryRequest.getSpaceId();
        Date startEditTime = pictureQueryRequest.getStartEditTime();
        Date endEditTime = pictureQueryRequest.getEndEditTime();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();

        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            String keyword = escapeLike(searchText);
            queryWrapper.and(qw -> qw.like("name", keyword)
                    .or()
                    .like("introduction", keyword)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId, "spaceId");
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        // 最大宽高用 <=（和上面的精确匹配是两回事），配合前端的尺寸预设做范围筛选
        queryWrapper.le(ObjUtil.isNotEmpty(picWidthMax), "picWidth", picWidthMax);
        queryWrapper.le(ObjUtil.isNotEmpty(picHeightMax), "picHeight", picHeightMax);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        queryWrapper.ge(ObjUtil.isNotEmpty(startEditTime), "editTime", startEditTime);
        queryWrapper.lt(ObjUtil.isNotEmpty(endEditTime), "editTime", endEditTime);

        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                // 空标签会让条件变成 %""%，匹配不到任何行；直接跳过，不要让它把结果集清空
                if (StrUtil.isBlank(tag)) {
                    continue;
                }
                queryWrapper.like("tags", escapeLike("\"" + tag + "\""));
            }
        }
        // 排序
        if (StrUtil.isNotBlank(sortField) && SORT_FIELD_WHITELIST.contains(sortField)) {
            // 用 "ascend".equals(sortOrder) 而不是 sortOrder.equals("ascend")：
            // 后者在 sortOrder 为 null 时会 NPE（参数在 orderBy 调用前就被求值了）
            queryWrapper.orderBy(true, "ascend".equals(sortOrder), sortField);
            if (!"id".equals(sortField)) {
                // 追加 id 作为决胜字段。createTime 只精确到秒，同一秒内插入的多行之间没有稳定顺序，
                // 缺失 tiebreaker 会让 offset 分页出现重复行或漏行
                queryWrapper.orderByDesc("id");
            }
        } else {
            // 兜底：绝不能让查询处于「完全没有 ORDER BY」的状态，否则 MySQL 返回的行序不确定。
            // sortField 非空但不在白名单时也走这里（比如 picScale），
            // 否则它就退化成无序查询，反而比修复前更糟。
            queryWrapper.orderByDesc("createTime");
            queryWrapper.orderByDesc("id");
        }
        return queryWrapper;
    }

    /**
     * 图片审核
     *
     * @param pictureReviewRequest
     * @param loginUser
     */
    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        //1.校验参数
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        if (id == null || reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //2.判断 图片是否存在
        Picture oldPicture = pictureRepository.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        //3.校验审核状态是否重复
        if (oldPicture.getReviewStatus().equals(reviewStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");
        }
        //4.数据库操作
        Picture updatePicture = new Picture();
        BeanUtils.copyProperties(pictureReviewRequest, updatePicture);
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        boolean request = pictureRepository.updateById(updatePicture);
        ThrowUtils.throwIf(!request, ErrorCode.OPERATION_ERROR);
    }

    /**
     * 填充审核参数
     *
     * @param picture
     * @param loginUser
     */
    @Override
    public void fillReviewParams(Picture picture, User loginUser) {
        if (loginUser.isAdmin()) {
            //管理员自动过审核
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewMessage("管理员自动过审");
            picture.setReviewerId(loginUser.getId());
            picture.setReviewTime(new Date());
        } else {
            //非管理员,无论编辑还是修改变为待审核
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }

    /**
     * 按搜索词自动推断图片分类
     * <p>
     * 先按 {@link #CATEGORY_KEYWORD_RULES} 做关键词匹配；一条都没命中就归到「摄影」。
     * 分类现在只有 10 个，兜底不能再用搜索词本身，否则会凭空造出分类列表里没有的值。
     */
    @Override
    public String autoCategorize(String searchText) {
        if (StrUtil.isBlank(searchText)) {
            return null;
        }
        String keyword = searchText.trim().toLowerCase();
        for (Map.Entry<String, String> rule : CATEGORY_KEYWORD_RULES.entrySet()) {
            if (keyword.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        // 泛图片搜索兜底到摄影，保证结果一定是 10 个分类之一
        return "摄影";
    }

    /**
     * 批量抓取的标签归一化：去空白、去重、限个数、限长度
     * <p>
     * 库里 tags 是 varchar(512)，10 个 12 字的标签也远小于这个上限，
     * 这里做约束是为了避免用户误粘贴一大段文本把整批图片都撑失败。
     */
    private List<String> normalizeTags(List<String> tags) {
        if (CollUtil.isEmpty(tags)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> uniqueTags = new LinkedHashSet<>();
        for (String tag : tags) {
            if (StrUtil.isBlank(tag)) {
                continue;
            }
            String trimmed = tag.trim();
            if (trimmed.length() > 12) {
                trimmed = trimmed.substring(0, 12);
            }
            uniqueTags.add(trimmed);
            if (uniqueTags.size() >= 10) {
                break;
            }
        }
        return new ArrayList<>(uniqueTags);
    }

    /**
     * 批量抓取图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return
     */
    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        // 校验参数
        String searchText = pictureUploadByBatchRequest.getSearchText();
        ThrowUtils.throwIf(StrUtil.isBlank(searchText), ErrorCode.PARAMS_ERROR, "搜索词不能为空");
        Integer count = pictureUploadByBatchRequest.getCount();
        ThrowUtils.throwIf(count == null || count <= 0, ErrorCode.PARAMS_ERROR, "抓取数量必须大于 0");
        ThrowUtils.throwIf(count > 30, ErrorCode.PARAMS_ERROR, "最多30张图片");
        //名称前缀默认等于搜索关键词
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }
        // 名称起始序号：批量补图时接着已有数量编号，避免每批都从 1 开始
        Integer startIndex = pictureUploadByBatchRequest.getStartIndex();
        if (startIndex == null || startIndex < 0) {
            startIndex = 0;
        }
        // 分类：前端选了具体分类就用它，没选（留空）则按搜索词自动分类
        String category = pictureUploadByBatchRequest.getCategory();
        if (StrUtil.isBlank(category)) {
            category = this.autoCategorize(searchText);
            log.info("批量抓取未指定分类，按搜索词自动分类：{} -> {}", searchText, category);
        }
        // 标签：整批共用，留空则不打标签
        List<String> tags = pictureUploadByBatchRequest.getTags();

        /*
         * 为什么不用 Bing 了：
         * Bing 的 async 接口无论带不带 first/count，都只返回固定的 35 条，
         * 第二次抓同一个词必然和第一次大量重复。
         * 360 的图片搜索接口支持 sn 偏移，翻页后结果基本不重叠，才能做到「这次和上次不一样」。
         */
        String cursorKey = BATCH_CURSOR_KEY_PREFIX + DigestUtil.md5Hex(searchText);
        int offset = readBatchCursor(cursorKey);
        // 游标超过上限（或旧版本留下的脏数据）时回到第一页
        if (offset >= BATCH_MAX_OFFSET) {
            offset = 0;
        }
        int uploadCount = 0;
        int pageCount = 0;
        boolean requestFailed = false;

        while (uploadCount < count && pageCount < BATCH_MAX_PAGES) {
            JSONArray itemList;
            int total;
            try {
                JSONObject pageJson = fetchImageSearchPage(searchText, offset);
                itemList = pageJson.getJSONArray("list");
                total = pageJson.getInt("total", 0);
            } catch (Exception e) {
                log.error("抓取图片搜索失败，searchText = {}，offset = {}", searchText, offset, e);
                requestFailed = true;
                break;
            }
            if (itemList == null || itemList.isEmpty()) {
                // 翻过界了：下次从头开始，避免游标一直卡在空页上
                offset = 0;
                break;
            }
            int processed = 0;
            for (Object itemObj : itemList) {
                if (uploadCount >= count) {
                    break;
                }
                if (!(itemObj instanceof JSONObject)) {
                    continue;
                }
                processed++;
                JSONObject item = (JSONObject) itemObj;
                String originalUrl = normalizeImageUrl(item.getStr("img"));
                String thumbnailUrl = normalizeImageUrl(item.getStr("thumb"));
                if (StrUtil.isBlank(originalUrl) && StrUtil.isBlank(thumbnailUrl)) {
                    continue;
                }
                // 先占位再去重，避免两个并发的抓取任务同时上传同一张图
                String seenKey = StrUtil.blankToDefault(item.getStr("id"),
                        StrUtil.blankToDefault(originalUrl, thumbnailUrl));
                if (!claimBatchSeen(seenKey)) {
                    continue;
                }
                // 这一批图片共用同一个名称前缀、分类和标签
                String picName = namePrefix + (startIndex + uploadCount + 1);
                boolean uploaded = uploadBatchPicture(originalUrl, picName, category, tags, loginUser)
                        || uploadBatchPicture(thumbnailUrl, picName, category, tags, loginUser);
                if (uploaded) {
                    uploadCount++;
                } else {
                    // 这张图没上传成功，把占位还回去，下次还能重试
                    releaseBatchSeen(seenKey);
                }
            }
            // 只推进真正扫描过的条数：中途抓满 30 张时，后面的结果留给下一次
            offset += processed;
            pageCount++;
            int maxOffset = total > 0 ? Math.min(total, BATCH_MAX_OFFSET) : BATCH_MAX_OFFSET;
            if (maxOffset > 0 && offset >= maxOffset) {
                // 翻到底后回到第一页；已经抓过的图会被 seen 集合跳过
                offset = 0;
            }
            if (processed == 0) {
                break;
            }
        }
        writeBatchCursor(cursorKey, offset);
        expireBatchSeen();

        if (uploadCount == 0) {
            if (requestFailed) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "抓取图片失败，请稍后重试");
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "没有抓到新图片，这个关键词的图片可能已经抓完了，换个搜索词试试");
        }
        return uploadCount;
    }

    /**
     * 补全协议相对地址（//host/path）
     *
     * UrlPictureUpload 只接受 http:// 和 https:// 开头的地址，
     * 不处理的话这类图会直接被判成非法 URL。
     */
    private String normalizeImageUrl(String imageUrl) {
        if (StrUtil.startWith(imageUrl, "//")) {
            return "https:" + imageUrl;
        }
        return imageUrl;
    }

    /**
     * 请求 360 图片搜索的一页结果
     *
     * @param searchText 搜索词
     * @param offset     从第几条结果开始（sn）
     * @return 接口返回的 JSON，包含 total 和 list
     */
    private JSONObject fetchImageSearchPage(String searchText, int offset) {
        String url = BATCH_SEARCH_API
                + "?q=" + URLUtil.encodeQuery(searchText)
                + "&src=srp&sn=" + offset + "&pn=30";
        HttpResponse response = HttpUtil.createGet(url)
                .header("User-Agent", BATCH_USER_AGENT)
                .header("Referer", "https://image.so.com/")
                .timeout(20000)
                .execute();
        try {
            ThrowUtils.throwIf(response.getStatus() != 200, ErrorCode.OPERATION_ERROR,
                    "图片搜索接口返回异常：" + response.getStatus());
            String body = response.body();
            ThrowUtils.throwIf(StrUtil.isBlank(body), ErrorCode.OPERATION_ERROR, "图片搜索接口返回为空");
            return JSONUtil.parseObj(body);
        } finally {
            response.close();
        }
    }

    /**
     * 读取某个搜索词下次抓取的起始偏移量
     */
    private int readBatchCursor(String cursorKey) {
        try {
            String value = stringRedisTemplate.opsForValue().get(cursorKey);
            return StrUtil.isBlank(value) ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (Exception e) {
            // Redis 不可用时退化成每次从第一页抓，靠 seen 集合尽量去重
            log.warn("读取批量抓取游标失败，按 0 处理：{}", cursorKey, e);
            return 0;
        }
    }

    /**
     * 保存搜索词下次抓取的起始偏移量
     */
    private void writeBatchCursor(String cursorKey, int offset) {
        try {
            stringRedisTemplate.opsForValue().set(cursorKey, String.valueOf(offset), 90, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("保存批量抓取游标失败：{}", cursorKey, e);
        }
    }

    /**
     * 抢占一个「这张图还没抓过」的标记
     *
     * @return true 表示可以抓；false 表示之前已经抓过
     */
    private boolean claimBatchSeen(String seenKey) {
        if (StrUtil.isBlank(seenKey)) {
            return true;
        }
        try {
            Long added = stringRedisTemplate.opsForSet().add(BATCH_SEEN_KEY, seenKey);
            return added == null || added > 0;
        } catch (Exception e) {
            // Redis 出问题时宁可重复也不要把抓取整个卡死
            log.warn("写入批量抓取去重标记失败：{}", seenKey, e);
            return true;
        }
    }

    /**
     * 上传失败时把去重标记还回去，下次可以重试
     */
    private void releaseBatchSeen(String seenKey) {
        if (StrUtil.isBlank(seenKey)) {
            return;
        }
        try {
            stringRedisTemplate.opsForSet().remove(BATCH_SEEN_KEY, seenKey);
        } catch (Exception e) {
            log.warn("释放批量抓取去重标记失败：{}", seenKey, e);
        }
    }

    /**
     * 延长去重集合的过期时间。没有这个集合就只能抓到重复图，所以给足 180 天。
     */
    private void expireBatchSeen() {
        try {
            stringRedisTemplate.expire(BATCH_SEEN_KEY, 180, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("设置批量抓取去重集合过期时间失败", e);
        }
    }

    /**
     * 抓取并上传一张图片
     *
     * @param fileUrl   图片地址
     * @param picName   图片名称
     * @param category  分类
     * @param tags      标签
     * @param loginUser 上传用户
     * @return 是否上传成功
     */
    private boolean uploadBatchPicture(String fileUrl, String picName, String category,
                                       List<String> tags, User loginUser) {
        if (StrUtil.isBlank(fileUrl)) {
            log.info("当前链接为空，已跳过: {}", fileUrl);
            return false;
        }
        PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
        pictureUploadRequest.setFileUrl(fileUrl);
        pictureUploadRequest.setPicName(picName);
        pictureUploadRequest.setCategory(category);
        pictureUploadRequest.setTags(tags);
        try {
            PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
            log.info("图片上传成功, id = {}, url = {}", pictureVO.getId(), fileUrl);
            return true;
        } catch (Exception e) {
            // 抓到原图失败是常见情况（原站防盗链、图片太大），退回缩略图试试，不算严重错误
            log.warn("图片上传失败，url = {}，原因：{}", fileUrl, e.getMessage());
            return false;
        }
    }

    /**
     * 清理图片文件
     *
     * @param oldPicture
     */
    @Override
    public void clearPictureFile(Picture oldPicture) {
        // 判断改图片是否被多条记录使用
        String pictureUrl = oldPicture.getUrl();
        long count = pictureRepository.lambdaQuery()
                .eq(Picture::getUrl, pictureUrl)
                .count();
        // 有不止一条记录用到了该图片，不清理
        if (count > 1) {
            return;
        }
        // 删除图片
        cosManager.deleteObject(pictureUrl);
        // 删除缩略图
        String thumbnailUrl = oldPicture.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl)) {
            cosManager.deleteObject(thumbnailUrl);
        }
    }

    /**
     * 删除图片
     *
     * @param pictureId
     * @param loginUser
     */
    @Override
    public void deletePicture(long pictureId, User loginUser) {
        ThrowUtils.throwIf(pictureId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 判断是否存在
        Picture oldPicture = pictureRepository.getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        // 校验权限
        //已经改为使用注解鉴权
        // checkPictureAuth(loginUser, oldPicture);

        // 开启事务
        transactionTemplate.execute(status -> {
            // 操作数据库
            boolean result = pictureRepository.removeById(pictureId);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
            // 释放额度。必须先判空：公共图库图片的 spaceId 为 null，
            // 直接 eq(Space::getId, null) 会生成 where id = null、一行都更新不到，
            // update 返回 false 就抛异常回滚，公共图库的图片会永远删不掉
            Long spaceId = oldPicture.getSpaceId();
            if (spaceId != null) {
                boolean update = spaceApplicationService.lambdaUpdate()
                        .eq(Space::getId, spaceId)
                        .setSql("totalSize = totalSize - " + oldPicture.getPicSize())
                        .setSql("totalCount = totalCount - 1")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return true;
        });
        // 异步清理文件。
        // 注意这里有可能出现对象存储上的图片文件实际没被清理的情况，但用户不应该感受到
        // 「删了图片空间却没释放额度」，所以没有把清理文件放进事务，靠定时任务做补偿
        this.clearPictureFile(oldPicture);
    }

    /**
     * 编辑图片
     *
     * @param picture
     * @param loginUser
     */
    @Override
    public void editPicture(Picture picture, User loginUser) {
        // 设置编辑时间
        picture.setEditTime(new Date());
        // 数据校验
        picture.validPicture();
        // 判断是否存在
        long id = picture.getId();
        Picture oldPicture = pictureRepository.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        // 校验权限
        //已经改为使用注解鉴权
        //checkPictureAuth(loginUser, oldPicture);

        // 补充审核参数
        this.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = pictureRepository.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }

    /**
     * 批量编辑图片（分类、标签、名称）
     * <p>
     * 批量操作有两条必须守住的红线：权限和原子性。
     * 权限上交由 @SaSpaceCheckPermission(picture:edit) 统一校验（团队空间的编辑者/管理员可用），
     * 查询时再用 spaceId 和 id 列表一起过滤，避免拿着自己空间里的 id 去改别人空间（或公共图库）的图片；
     * 原子性上，整批走一个事务，中途有一条失败就整体回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser) {
        // 1. 获取和校验参数
        List<Long> pictureIdList = pictureEditByBatchRequest.getPictureIdList();
        Long spaceId = pictureEditByBatchRequest.getSpaceId();
        String category = pictureEditByBatchRequest.getCategory();
        List<String> tags = pictureEditByBatchRequest.getTags();
        ThrowUtils.throwIf(spaceId == null || CollUtil.isEmpty(pictureIdList), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 2. 校验空间是否存在（权限交给 @SaSpaceCheckPermission(picture:edit) 统一校验，
        //    团队空间的编辑者/管理员也能批量编辑，不能再按空间创建人判断）
        Space space = spaceApplicationService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 3. 查询指定图片，仅选择需要的字段
        // 只查 id 和 spaceId：后面要改的字段是现算出来的，其余字段（url、picSize 等）根本用不到，
        // 查回来再整条写回去还会把并发改动的其它字段覆盖掉
        List<Picture> pictureList = pictureRepository.lambdaQuery()
                .select(Picture::getId, Picture::getSpaceId)
                .eq(Picture::getSpaceId, spaceId)
                .in(Picture::getId, pictureIdList)
                .list();
        if (pictureList.isEmpty()) {
            return;
        }
        // 4. 更新分类和标签
        // 用 isNotBlank / isNotEmpty 判断：传空值表示「这一项不改」，而不是「清空」
        pictureList.forEach(picture -> {
            if (StrUtil.isNotBlank(category)) {
                picture.setCategory(category);
            }
            if (CollUtil.isNotEmpty(tags)) {
                // 和 Picture.tags 的存储格式保持一致：JSON 数组字符串
                picture.setTags(JSONUtil.toJsonStr(tags));
            }
        });
        // 批量重命名
        String nameRule = pictureEditByBatchRequest.getNameRule();
        fillPictureWithNameRule(pictureList, nameRule);
        // 5. 操作数据库进行批量更新
        // 用 updateBatchById（一条 SQL 批处理）而不是 for 循环逐条 updateById：
        // 循环是一张图一次数据库往返，批量编辑几十张图时代价很明显
        boolean result = pictureRepository.updateBatchById(pictureList);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "批量编辑失败");
    }

    /**
     * 按命名规则填充图片名称
     * <p>
     * nameRule 里的 {序号} 是动态变量，按列表顺序从 1 开始递增替换，例如「图片_{序号}」。
     *
     * @param pictureList 待填充的图片（顺序即序号顺序）
     * @param nameRule    命名规则，为空表示不重命名
     */
    private void fillPictureWithNameRule(List<Picture> pictureList, String nameRule) {
        if (CollUtil.isEmpty(pictureList) || StrUtil.isBlank(nameRule)) {
            return;
        }
        long count = 1;
        try {
            for (Picture picture : pictureList) {
                String pictureName = nameRule.replaceAll("\\{序号}", String.valueOf(count++));
                picture.setName(pictureName);
            }
        } catch (Exception e) {
            log.error("名称解析错误", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "名称解析错误");
        }
    }

    /**
     * 校验空间图片权限
     *
     * @param loginUser
     * @param picture
     */
    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            // 公共图库，仅本人或管理员可操作
            if (!picture.getUserId().equals(loginUser.getId()) && !loginUser.isAdmin()) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        } else {
            // 私有空间，仅空间管理员可操作
            if (!picture.getUserId().equals(loginUser.getId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
    }

    /**
     * 根据颜色搜索图片
     *
     * @param spaceId
     * @param picColor
     * @param loginUser
     * @return
     */
    @Override
    public List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser) {
        // 1. 校验参数
        ThrowUtils.throwIf(spaceId == null || StrUtil.isBlank(picColor), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 2. 校验空间是否存在（查看权限交给 @SaSpaceCheckPermission(picture:view) 统一校验，
        //    团队空间的浏览者/编辑者/管理员都该能按颜色搜索，不能再按空间创建人判断）
        Space space = spaceApplicationService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 3. 查询该空间下所有图片（必须有主色调）
        List<Picture> pictureList = pictureRepository.lambdaQuery()
                .eq(Picture::getSpaceId, spaceId)
                .isNotNull(Picture::getPicColor)
                .list();
        // 如果没有图片，直接返回空列表
        if (CollUtil.isEmpty(pictureList)) {
            return Collections.emptyList();
        }
        // 将目标颜色转为 Color 对象
        Color targetColor = Color.decode(picColor);
        // 4. 计算相似度并排序
        List<Picture> sortedPictures = pictureList.stream()
                .sorted(Comparator.comparingDouble(picture -> {
                    // 提取图片主色调
                    String hexColor = picture.getPicColor();
                    // 没有主色调的图片放到最后
                    if (StrUtil.isBlank(hexColor)) {
                        return Double.MAX_VALUE;
                    }
                    Color pictureColor = Color.decode(hexColor);
                    // 越大越相似
                    return -ColorSimilarUtils.calculateSimilarity(targetColor, pictureColor);
                }))
                // 取前 12 个
                .limit(12)
                .collect(Collectors.toList());

        // 转换为 PictureVO
        return sortedPictures.stream()
                .map(PictureVO::objToVo)
                .collect(Collectors.toList());
    }

    /**
     * 扩图的默认提示词
     * <p>新模型（wanx2.1-imageedit）的 prompt 是必填项，用来描述「扩展出来的区域画什么」，
     * 不会改变原图内容。以后如果要让用户自己填，把这个值挪到请求参数里即可。</p>
     */
    private static final String DEFAULT_OUT_PAINTING_PROMPT =
            "在图片四周自然延伸画面，保持原图的风格、光影、色调和构图一致，不要改变原图内容。";

    /**
     * 扩图接口对输入图片的尺寸要求：宽、高都要在 512 ~ 4096 像素之间
     * <p>接口的报错只会提宽（The width of the image should be between 512 and 4096 pixels），
     * 这里按同比例缩放，宽高一起管，省得再踩到高度那条。</p>
     */
    private static final int OUT_PAINTING_MIN_SIDE = 512;
    private static final int OUT_PAINTING_MAX_SIDE = 4096;

    /**
     * 创建扩图任务
     *
     * @param createPictureOutPaintingTaskRequest
     * @param loginUser
     */
    @Override
    public CreateOutPaintingTaskResponse createPictureOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser) {
        // 获取图片信息
        Long pictureId = createPictureOutPaintingTaskRequest.getPictureId();
        Picture picture = Optional.ofNullable(pictureRepository.getById(pictureId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        // 权限校验
        //已经改为使用注解鉴权
        //checkPictureAuth(loginUser, picture);
        // 构造请求参数
        CreateOutPaintingTaskRequest taskRequest = new CreateOutPaintingTaskRequest();
        CreateOutPaintingTaskRequest.Input input = new CreateOutPaintingTaskRequest.Input();
        // 扩图固定用 expand 功能，图片走 base_image_url
        input.setFunction("expand");
        input.setPrompt(DEFAULT_OUT_PAINTING_PROMPT);
        input.setBaseImageUrl(buildOutPaintingImageUrl(picture));
        taskRequest.setInput(input);
        taskRequest.setParameters(createPictureOutPaintingTaskRequest.getParameters());
        // 创建任务
        return aliYunAiApi.createOutPaintingTask(taskRequest);
    }

    /**
     * 构造送给扩图接口的图片地址
     * <p>图库里很多图片是批量抓来的缩略图（宽 474 之类），比接口要求的最小 512 还小，
     * 直接送过去必然失败；尺寸超过 4096 的图同理。这里在地址后面挂一个对象存储的图片处理参数，
     * 让对象存储临时缩放到合法范围：不修改原图、不额外占存储，返回的依然是带后缀、Content-Type 正确的图片。</p>
     *
     * @param picture 要扩图的图片
     * @return 尺寸一定符合接口要求的图片地址
     */
    private String buildOutPaintingImageUrl(Picture picture) {
        String url = picture.getUrl();
        Integer width = picture.getPicWidth();
        Integer height = picture.getPicHeight();
        if (width == null || height == null || width <= 0 || height <= 0) {
            // 没有尺寸信息就没法判断，直接把地址交出去，让接口自己报错
            return url;
        }
        int shortSide = Math.min(width, height);
        int longSide = Math.max(width, height);
        if (shortSide >= OUT_PAINTING_MIN_SIDE && longSide <= OUT_PAINTING_MAX_SIDE) {
            // 尺寸本来就合规，不做多余的缩放，免得白白损失画质
            return url;
        }
        // 缩放时必须让「短边不小于 512」和「长边不大于 4096」同时成立，
        // 两个条件都满足才算有解：长短边之比不能超过 4096 / 512
        double minScale = (double) OUT_PAINTING_MIN_SIDE / shortSide;
        double maxScale = (double) OUT_PAINTING_MAX_SIDE / longSide;
        if (minScale > maxScale) {
            log.error("图片尺寸 {}x{} 的长短边之比超出扩图接口能接受的极限，无法缩放", width, height);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "这张图片太狭长了，AI 扩图处理不了，请换一张比例正常一点的图片");
        }
        double scale = Math.max(minScale, Math.min(1.0, maxScale));
        int targetWidth = (int) Math.round(width * scale);
        int targetHeight = (int) Math.round(height * scale);
        // imageMogr2 是对象存储的图片处理参数，语义是「等比缩放，让宽高都不超过给定值」
        String scaledUrl = String.format("%s?imageMogr2/thumbnail/%dx%d", url, targetWidth, targetHeight);
        log.info("扩图前把图片缩放到 {}x{}（原图 {}x{}）：{}", targetWidth, targetHeight, width, height, scaledUrl);
        return scaledUrl;
    }
}
