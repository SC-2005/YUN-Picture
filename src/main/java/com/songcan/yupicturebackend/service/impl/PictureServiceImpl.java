package com.songcan.yupicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.songcan.yupicturebackend.exception.BusinessException;
import com.songcan.yupicturebackend.exception.ErrorCode;
import com.songcan.yupicturebackend.exception.ThrowUtils;
import com.songcan.yupicturebackend.manager.CosManager;
import com.songcan.yupicturebackend.manager.upload.FilePictureUpload;
import com.songcan.yupicturebackend.manager.upload.PictureUploadTemplate;
import com.songcan.yupicturebackend.manager.upload.UrlPictureUpload;
import com.songcan.yupicturebackend.mapper.PictureMapper;
import com.songcan.yupicturebackend.model.dto.file.UploadPictureResult;
import com.songcan.yupicturebackend.model.dto.picture.PictureQueryRequest;
import com.songcan.yupicturebackend.model.dto.picture.PictureReviewRequest;
import com.songcan.yupicturebackend.model.dto.picture.PictureUploadByBatchRequest;
import com.songcan.yupicturebackend.model.dto.picture.PictureUploadRequest;
import com.songcan.yupicturebackend.model.entity.Picture;
import com.songcan.yupicturebackend.model.entity.User;
import com.songcan.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.songcan.yupicturebackend.model.vo.PictureVO;
import com.songcan.yupicturebackend.model.vo.UserVO;
import com.songcan.yupicturebackend.service.PictureService;
import com.songcan.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 34451
 * @description 针对表【picture(图片)】的数据库操作Service实现
 * @createDate 2026-09-09 19:15:40
 */
@Slf4j
@Service
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
        implements PictureService {

    @Resource
    private UserService userService;

    @Resource
    private FilePictureUpload filePictureUpload;

    @Resource
    private UrlPictureUpload urlPictureUpload;

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
        put("表情", "表情包");
        put("emoji", "表情包");
        put("meme", "表情包");
        put("搞笑", "表情包");
        put("海报", "海报");
        put("poster", "海报");
        put("banner", "海报");
        put("宣传", "海报");
        put("电商", "电商");
        put("商品", "电商");
        put("产品", "电商");
        put("购物", "电商");
        put("模特", "电商");
        put("模板", "模板");
        put("template", "模板");
        put("ppt", "模板");
        put("简历", "模板");
        put("素材", "素材");
        put("背景", "素材");
        put("壁纸", "素材");
        put("头像", "素材");
        put("icon", "素材");
        put("图标", "素材");
    }};
    @Autowired
    private CosManager cosManager;

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

    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR);
        }
    }

    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {
        //校验参数
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        //判断是上传还是删除
        Long picturId = null;
        if (pictureUploadRequest != null) {
            picturId = pictureUploadRequest.getId();
        }
        Picture oldPicture = null;
        //如果是更新,判断图片是否存在
        if (picturId != null) {
            oldPicture = this.getById(picturId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 判断归属要比图片的 userId，不是图片自己的 id
            if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
        //上传图片
        // 上传图片，得到图片信息 // 按照用户 id 划分目录
        String uploadPathPrefix = String.format("public/%s", loginUser.getId());

        // 根据 inputSource 的类型区分上传方式
        PictureUploadTemplate pictureUploadTemplate = filePictureUpload;
        if (inputSource instanceof String) {
            pictureUploadTemplate = urlPictureUpload;
        }
        UploadPictureResult uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        // 构造要入库的图片信息
        Picture picture = new Picture();
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
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        // 更新时沿用原作者的归属：管理员有权改别人的图，但归属不该被改写成管理员自己
        picture.setUserId(oldPicture != null ? oldPicture.getUserId() : loginUser.getId());
        //补充审核参数
        this.fillReviewParams(picture, loginUser);
        //操作数据库
        //如果picturId不为空的话,说明是更新操作
        if (picturId != null) {
            picture.setId(picturId);
            picture.setUpdateTime(new Date());
        }
        boolean result = this.saveOrUpdate(picture);
        // 先确认入库成功，再清理旧图片资源：反过来的话，写库失败会把旧图删掉，
        // 而记录仍指向旧 url，图片就彻底找不回来了
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "用户上传失败,数据库操作失败");
        //如果是更新操作,则需要清理旧图片资源
        if (picturId != null && oldPicture != null) {
            this.clearPictureFile(oldPicture);
        }
        return PictureVO.objToVo(picture);
    }


    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }


    /**
     * 分页获取图片封装
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        pictureVOPage.setRecords(getPictureVOList(picturePage.getRecords()));
        return pictureVOPage;
    }

    /**
     * 获取图片封装类(列表)
     */
    @Override
    public List<PictureVO> getPictureVOList(List<Picture> pictureList) {
        if (CollUtil.isEmpty(pictureList)) {
            return new ArrayList<>();
        }
        // 对象列表 => 封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        //    先滤掉 null 的 userId：groupingBy 的 classifier 返回 null 会抛 NPE
        Set<Long> userIdSet = pictureList.stream()
                .map(Picture::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        //    listByIds 收到空集合会生成 IN ()，MySQL 语法错误，所以空集合要短路掉
        Map<Long, List<User>> userIdUserListMap = userIdSet.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIdSet).stream().collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if (userId != null && userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        return pictureVOList;
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
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
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
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);


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
        String reviewMessage = pictureReviewRequest.getReviewMessage();
        if (id == null || reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //2.判断 图片是否存在
        Picture oldPicture = this.getById(id);
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
        boolean request = this.updateById(updatePicture);
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
        if (userService.isAdmin(loginUser)) {
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
     * 先按 {@link #CATEGORY_KEYWORD_RULES} 做关键词匹配；一条都没命中就直接拿搜索词当分类，
     * 保证每批抓取的图片都能被分到某一类（而不是全堆进默认分类里）。
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
        // 兜底：搜索词本身就是最贴近这批图片的分类，截断一下防止超长
        String fallback = searchText.trim();
        return fallback.length() > 64 ? fallback.substring(0, 64) : fallback;
    }

    /**
     * 批量抓取图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return
     */
    @Override
    public Integer UploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
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
        // 分类：前端选了具体分类就用它，没选（留空）则按搜索词自动分类
        String category = pictureUploadByBatchRequest.getCategory();
        if (StrUtil.isBlank(category)) {
            category = this.autoCategorize(searchText);
            log.info("批量抓取未指定分类，按搜索词自动分类：{} -> {}", searchText, category);
        }
        //抓取内容
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", searchText);
        Document document;
        try {
            document = Jsoup.connect(fetchUrl).get();
        } catch (IOException e) {
            log.error("抓取页面失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "抓取页面失败");
        }
        //解析内容
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isEmpty(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取元素失败");
        }
        Elements imgElementList = div.select("img.mimg");
        // 遍历元素，依次处理上传图片
        int uploadCount = 0;
        for (Element imgElement : imgElementList) {
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过: {}", fileUrl);
                continue;
            }
            // 处理图片的地址，防止转义或者和对象存储冲突的问题
            // codefather.cn?yupi=dog，应该只保留 codefather.cn
            int questionMarkIndex = fileUrl.indexOf("?");
            if (questionMarkIndex > -1) {
                fileUrl = fileUrl.substring(0, questionMarkIndex);
            }
            // 上传图片
            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
            pictureUploadRequest.setFileUrl(fileUrl);
            pictureUploadRequest.setPicName(namePrefix + (uploadCount + 1));
            // 分类由「用户选择」或「自动推断」决定，这一批图片共用同一个分类
            pictureUploadRequest.setCategory(category);
            try {
                PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
                log.info("图片上传成功, id = {}", pictureVO.getId());
                uploadCount++;
            } catch (Exception e) {
                log.error("图片上传失败", e);
                continue;
            }
            if (uploadCount >= count) {
                break;
            }
        }
        return uploadCount;
    }

    @Async
    @Override
    public void clearPictureFile(Picture oldPicture) {
        // 判断改图片是否被多条记录使用
        String pictureUrl = oldPicture.getUrl();
        long count = this.lambdaQuery()
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
}






