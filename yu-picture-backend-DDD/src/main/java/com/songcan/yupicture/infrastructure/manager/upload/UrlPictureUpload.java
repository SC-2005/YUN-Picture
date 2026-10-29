package com.songcan.yupicture.infrastructure.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.infrastructure.exception.ThrowUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 * Url图片上传
 */
@Service
public class UrlPictureUpload extends PictureUploadTemplate {
    @Override
    protected String getOriginalFilename(Object inputSource) {
        String fileUrl = (String) inputSource;
        // 取链接路径里的文件名，而且必须带上后缀：
        // 1. 先去掉 ? 和 # 后面的参数，不然后缀里会混进一串查询参数（https://xx.com/1.jpg?x=1 -> 1.jpg）
        // 2. 原来的写法取的是不带后缀的文件名（mainName），导致上传到对象存储的 key 以 "." 结尾、
        //    没有任何图片后缀，访问时 Content-Type 是 application/octet-stream，AI 扩图之类的功能会不认
        // 3. 链接里本来就没有后缀（比如 https://xx.com/pic/8f3c）时取到空后缀，
        //    由 PictureUploadTemplate 根据文件内容判断真实格式
        String urlWithoutParams = StrUtil.subBefore(fileUrl, '?', false);
        urlWithoutParams = StrUtil.subBefore(urlWithoutParams, '#', false);
        return FileUtil.getName(urlWithoutParams);
    }

    @Override
    protected void validPicture(Object inputSource) {
        String fileUrl = (String) inputSource;

        //1.校验非空
        ThrowUtils.throwIf(StrUtil.isBlank(fileUrl), ErrorCode.PARAMS_ERROR, "文件url不能为空");

        //2.校验url格式
        try {
            new URL(fileUrl);
        } catch (MalformedURLException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件url格式错误");
        }

        //3.校验url协议
        ThrowUtils.throwIf(!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://"),
                ErrorCode.PARAMS_ERROR, "仅支持http和https协议的url");

        //4.发送HEAD请求
        HttpResponse httpResponse = null;
        try {
            httpResponse = HttpUtil.createRequest(Method.HEAD, fileUrl)
                    .execute();
            // 未正常返回，无需执行其他判断
            if (httpResponse.getStatus() != HttpStatus.HTTP_OK) {
                return;
            }
            //5.文件存在,文件类型校验
            String contentType = httpResponse.header("Content-Type");
            // 不为空，才校验是否合法，这样校验规则相对宽松
            if (StrUtil.isNotBlank(contentType)) {
                // 允许的图片类型
                final List<String> ALLOW_CONTENT_TYPES = Arrays.asList("image/jpeg", "image/jpg", "image/png", "image/webp");
                ThrowUtils.throwIf(!ALLOW_CONTENT_TYPES.contains(contentType.toLowerCase()),
                        ErrorCode.PARAMS_ERROR, "文件类型错误");
            }
            //6.文件存在,文件大小校验
            String contentLengthStr = httpResponse.header("Content-Length");
            if (StrUtil.isNotBlank(contentLengthStr)) {
                try {
                    long contentLength = Long.parseLong(contentLengthStr);
                    final long ONE_M = 1024 * 1024;
                    ThrowUtils.throwIf(contentLength > 2 * ONE_M, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2MB");
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件大小格式错误");
                }
            }
        } finally {
            // 记得释放资源
            if (httpResponse != null) {
                httpResponse.close();
            }
        }

    }

    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        String fileUrl = (String) inputSource;
        //下载文件到临时目录
        HttpUtil.downloadFile(fileUrl, file);
    }
}
