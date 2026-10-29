package com.songcan.yupicture.infrastructure.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.songcan.yupicture.domain.picture.entity.Picture;
import com.songcan.yupicture.domain.picture.repository.PictureRepository;
import com.songcan.yupicture.infrastructure.mapper.PictureMapper;
import org.springframework.stereotype.Service;

/**
 * 图片仓储实现
 */
@Service
public class PictureRepositoryImpl extends ServiceImpl<PictureMapper, Picture> implements PictureRepository {
}
