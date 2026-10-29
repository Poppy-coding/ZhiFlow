package com.example.backend.service;

import com.example.backend.dto.MediaUploadResponse;
import com.example.backend.entity.MediaFile;
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
     * 查询媒体文件并校验文件是否属于指定用户。
     *
     * @param mediaId 媒体文件 ID
     * @param userId 用户 ID
     * @return 校验通过的媒体文件
     */
    MediaFile requireOwnedMedia(Long mediaId, Long userId);

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
