package com.example.backend.service;

import com.example.backend.dto.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MediaService {

    /**
     * 上传文件接口
     * @param file
     * @param userId
     * @return
     */
    MediaUploadResponse upload(MultipartFile file, Long userId);

    /**
     * 查看自己上传了文件列表
     * @param userId
     * @return
     */
    List<MediaUploadResponse> listByUser(Long userId);

    /**
     * 获取视频播放地址
     * @param id
     * @param userId
     * @return
     */
    String playback(Long id, Long userId);

    /**
     * 删除视频文件
     * @param id
     * @param userId
     */
    void delete(Long id, Long userId);
}
