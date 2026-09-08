package com.example.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.backend.dto.MediaUploadResponse;
import com.example.backend.entity.MediaFile;
import com.example.backend.mapper.MediaFileMapper;
import com.example.backend.service.MediaService;
import com.example.backend.utils.MinioUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class MediaServiceImpl implements MediaService {

    private static final Set<String> VIDEO_SUFFIXES = Set.of(
            ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v");

    @Resource
    private MinioUtils minioUtils;

    @Resource
    private MediaFileMapper mediaFileMapper;

    /**
     * 上传文件
     * @param file
     * @param userId
     * @return
     */
    @Override
    public MediaUploadResponse upload(MultipartFile file, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String filename = buildFileName(file.getOriginalFilename());
        String md5 = calculateMd5(file);//计算MD5摘要值
        String fileUrl = minioUtils.uploadFile(file);

        MediaFile mediaFile = new MediaFile();
        mediaFile.setUserId(userId);
        mediaFile.setFilename(filename);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setFilePath(fileUrl);
        mediaFile.setContentHash(md5);
        mediaFile.setUploadTime(LocalDateTime.now());

        try {
            mediaFileMapper.insert(mediaFile);
        } catch (RuntimeException e) {
            removeUploadedFile(fileUrl, e);
            throw e;
        }

        log.info("media_uploaded mediaId={} userId={} filename={}", mediaFile.getId(), userId, filename);
        return new MediaUploadResponse(
                mediaFile.getId(),
                mediaFile.getFilename(),
                mediaFile.getStatus(),
                mediaFile.getCoverUrl(),
                mediaFile.getUploadTime());
    }

    /**
     * 查看上传文件列表
     * @param userId
     * @return
     */
    @Override
    public List<MediaUploadResponse> listByUser(Long userId) {
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .orderByDesc("id");
        List<MediaFile> mediaFiles = mediaFileMapper.selectList(query);
        return mediaFiles.stream()
                .map(mediaFile -> new MediaUploadResponse(
                        mediaFile.getId(),
                        mediaFile.getFilename(),
                        mediaFile.getStatus(),
                        mediaFile.getCoverUrl(),
                        mediaFile.getUploadTime()))
                .toList();
    }

    /**
     * 获取视频播放地址
     * @param id
     * @param userId
     * @return
     */
    @Override
    public String playback(Long id, Long userId) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) {
            throw new NoSuchElementException("文件不存在");
        }
        if (!Objects.equals(mediaFile.getUserId(), userId)) {
            throw new SecurityException("无权访问该文件");
        }
        return minioUtils.readableSource(mediaFile.getFilePath());
    }

    @Override
    public void delete(Long id, Long userId) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if(mediaFile == null){
            throw new NoSuchElementException("文件不存在");
        }
        if (!Objects.equals(mediaFile.getUserId(), userId)) {
            throw new SecurityException("无权访问该文件");
        }
        mediaFileMapper.deleteById(id);//删除该文件

        //删除文件
        if (mediaFile.getFilePath() != null) {
            try {
                minioUtils.removeFile(mediaFile.getFilePath());
            } catch (RuntimeException e) {
                log.warn("minio清楚文件失败 mediaId={} path={}", id, mediaFile.getFilePath(), e);
            }
        }
    }

    /**
     * 创建filename
     * @param filename
     * @return
     */
    private String buildFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("视频文件名不能为空");
        }
        String normalized = filename.replace('\\', '/');//将//全部替换为/
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();//只要最终的文件名
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("视频文件名无效或过长");
        }
        //文件名后缀转化为小写
        String suffix = fileSuffix(normalized).toLowerCase(Locale.ROOT);
        if (!VIDEO_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅支持 MP4、MOV、MKV、AVI、WEBM 和 M4V 视频");
        }
        return normalized;
    }

    /**
     * 计算MD5的值
     * @param file
     * @return
     */
    private String calculateMd5(MultipartFile file) {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int read;
        try (InputStream inputStream = file.getInputStream()) {
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("文件 MD5 计算失败", e);
        }
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    //获取文件后缀名
    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private void removeUploadedFile(String fileUrl, RuntimeException originalError) {
        try {
            minioUtils.removeFile(fileUrl);
        } catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
            log.warn("uploaded_object_rollback_failed path={}", fileUrl, cleanupError);
        }
    }
}
