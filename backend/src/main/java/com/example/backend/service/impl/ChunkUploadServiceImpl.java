package com.example.backend.service.impl;

import com.example.backend.common.ErrorCode;
import com.example.backend.dto.MediaUploadResponse;
import com.example.backend.entity.MediaFile;
import com.example.backend.exception.BusinessException;
import com.example.backend.mapper.MediaFileMapper;
import com.example.backend.service.ChunkUploadService;
import com.example.backend.utils.MinioUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
@Slf4j
@Service
public class ChunkUploadServiceImpl implements ChunkUploadService {

    private static final String UPLOAD_KEY_PREFIX = "upload:chunked:";
    private static final long MAX_CHUNK_BYTES = 5L * 1024 * 1024;
    private static final int MAX_TOTAL_CHUNKS = 410;
    private static final Set<String> VIDEO_SUFFIXES = Set.of(".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v");
    private static final String LOCK_MERGE_KEY = "lock:upload:merge:";

    @Resource
    private MediaFileMapper mediaFileMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private MinioUtils minioUtils;
    @Resource
    private RedissonClient redisson;

    /**
     * 初始化分片上传
     * @param filename
     * @param totalChunks
     * @param userId
     * @return
     */
    @Override
    public String initialize(String filename, int totalChunks, Long userId) {
        String normalizedFilename = buildFileName(filename);//创建filename
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("totalChunks 必须在 1 到 " + MAX_TOTAL_CHUNKS + " 之间");
        }
        if (userId == null) {
            throw new IllegalArgumentException("用户不能为空");
        }

        String uploadId = UUID.randomUUID().toString();//获取上传的id，用于放入reids
        Map<String, String> metadata = new HashMap<>();//元数据
        metadata.put("filename", normalizedFilename);
        metadata.put("totalChunks", String.valueOf(totalChunks));
        metadata.put("userId", String.valueOf(userId));
        stringRedisTemplate.opsForHash().putAll(uploadKey(uploadId), metadata);//将该上传id放入redis中
        stringRedisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);//设置过期时间为1天
        return uploadId;
    }

    /**
     * 查询已经上传的所有分片号
     * @param uploadId 上传任务ID
     * @param userId 用户ID
     * @return
     */
    @Override
    public Set<Integer> uploadChunks(String uploadId, Long userId) {
        requireUpload(uploadId, userId);//验证uploadId 与 userId是否有效
        Set<String> members = stringRedisTemplate.opsForSet().members(partsKey(uploadId));
        Set<Integer> result = new TreeSet<>();
        if (members != null) {
            for (String member : members) {
                result.add(Integer.parseInt(member));
            }
        }
        return result;
    }

    /**
     * 上传单个分片上传
     * @param uploadId
     * @param chunkIndex
     * @param totalChunks
     * @param chunk
     * @param userId
     */
    @Override
    public void uploadChunk(String uploadId, int chunkIndex, int totalChunks, MultipartFile chunk, Long userId) {
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("上传分块不能为空");
        }
        if (chunk.getSize() > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("单个上传分块不能超过5MB");
        }
        Map<Object, Object> medata = requireUpload(uploadId, userId);
        int expectChunks = Integer.parseInt(String.valueOf(medata.get("totalChunks")));
        if (expectChunks != totalChunks) {
            throw new IllegalArgumentException("上传总数目不正确");
        }
        if (chunkIndex < 0 || chunkIndex >= expectChunks) {
            throw new IllegalArgumentException("当前上传块的索引不正常");
        }
        //开始上传分片
        String objectName = chunkObjectName(uploadId, chunkIndex);
        String partNumber = String.valueOf(chunkIndex);
        minioUtils.uploadObject(objectName, chunk);//最终为 chunk-uploads/uploadId/part-chunkIndex
        try {
            Long added = stringRedisTemplate.opsForSet().add(partsKey(uploadId), partNumber);
            Boolean partsExpired = stringRedisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
            Boolean uploadExpired = stringRedisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
            if (added == null || !Boolean.TRUE.equals(partsExpired) || !Boolean.TRUE.equals(uploadExpired)) {
                throw new IllegalStateException("Redis分片状态写入失败");
            }
        } catch (RuntimeException redisException) {
            try {
                minioUtils.removeObject(objectName);
            } catch (RuntimeException cleanupException) {
                redisException.addSuppressed(cleanupException);
            }
            try {
                stringRedisTemplate.opsForSet().remove(partsKey(uploadId), partNumber);
            } catch (RuntimeException cleanupException) {
                redisException.addSuppressed(cleanupException);
            }
            throw new IllegalStateException("记录分片上传状态失败，请重新上传该分片", redisException);
        }
    }

    /**
     * 实现完整的分片上传
     * @param uploadId
     * @param userId
     * @return
     */
    @Override
    public MediaUploadResponse completeUpload(String uploadId, Long userId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("上传文件id为空");
        }
        if (userId == null) {
            throw new IllegalArgumentException("上传用户Id为空");
        }
        //获取分布式锁
        RLock lock = redisson.getLock(LOCK_MERGE_KEY + uploadId);
        if (!lock.tryLock()) {
            throw new BusinessException(ErrorCode.CONFLICT, "合并任务正在进行，请稍后重试");
        }
        try {
            MediaUploadResponse completed = completedUpload(uploadId, userId);
            if (completed != null) {
                return completed;
            }

            Map<Object, Object> metadata = requireUpload(uploadId, userId);
            String filename = String.valueOf(metadata.get("filename"));
            int totalChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
            Set<Integer> uploaded = uploadChunks(uploadId, userId);
            List<Integer> missingChunks = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                if (!uploaded.contains(i)) {
                    missingChunks.add(i);
                }
            }
            if (!missingChunks.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "上传分片不完整，缺少分片：" + missingChunks);
            }

            Path tempFile = null;
            try {
                //创建一个本地文件 用于接受合并的视频
                tempFile = Files.createTempFile("ZhiFlow-merged-", fileSuffix(filename));
                MessageDigest digest = md5Digest();
                try (OutputStream fileOutput = Files.newOutputStream(tempFile);
                     DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
                     BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
                    for (int i = 0; i < totalChunks; i++) {
                        minioUtils.minioToOutput(chunkObjectName(uploadId, i), output);
                    }
                }

                String md5 = HexFormat.of().formatHex(digest.digest());
                String fileUrl = minioUtils.uploadLocalFile(tempFile.toFile(), filename);
                MediaFile mediaFile = new MediaFile();
                mediaFile.setUserId(userId);
                mediaFile.setStatus("COMPLETED");
                mediaFile.setUploadTime(LocalDateTime.now());
                mediaFile.setFilename(filename);
                mediaFile.setContentHash(md5);
                mediaFile.setFilePath(fileUrl);
                try {
                    int inserted = mediaFileMapper.insert(mediaFile);
                    if (inserted != 1) {
                        throw new IllegalStateException("媒体文件保存失败");
                    }
                } catch (RuntimeException databaseException) {
                    removeCompletedFile(fileUrl, databaseException);
                    throw databaseException;
                }

                MediaUploadResponse response = toUploadResponse(mediaFile);
                stringRedisTemplate.opsForValue().set(
                        completedKey(uploadId),
                        String.valueOf(response.getId()),
                        1,
                        TimeUnit.DAYS
                );
                cleanupUpload(uploadId, totalChunks, response.getId());
                return response;
            } catch (IOException e) {
                throw new IllegalStateException("合并分片失败", e);
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException e) {
                        log.warn("删除合并临时文件失败 path={}", tempFile, e);
                    }
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private MediaUploadResponse completedUpload(String uploadId, Long userId) {
        String mediaId = stringRedisTemplate.opsForValue().get(completedKey(uploadId));
        if (mediaId == null) {
            return null;
        }
        try {
            MediaFile mediaFile = mediaFileMapper.selectById(Long.valueOf(mediaId));
            if (mediaFile == null) {
                stringRedisTemplate.delete(completedKey(uploadId));
                return null;
            }
            if (!Objects.equals(mediaFile.getUserId(), userId)) {
                throw new SecurityException("无权访问该上传结果");
            }
            return toUploadResponse(mediaFile);
        } catch (NumberFormatException e) {
            stringRedisTemplate.delete(completedKey(uploadId));
            return null;
        }
    }

    /**
     * 将mediaFile转化为mediaUploadResponse
     * @param mediaFile
     * @return
     */
    private MediaUploadResponse toUploadResponse(MediaFile mediaFile) {
        return MediaUploadResponse.builder()
                .id(mediaFile.getId())
                .filename(mediaFile.getFilename())
                .status(mediaFile.getStatus())
                .coverUrl(mediaFile.getCoverUrl())
                .uploadTime(mediaFile.getUploadTime())
                .build();
    }

    private void cleanupUpload(String uploadId, int totalChunks, Long mediaId) {
        for (int i = 0; i < totalChunks; i++) {
            try {
                minioUtils.removeObject(chunkObjectName(uploadId, i));
            } catch (RuntimeException e) {
                log.warn("删除临时分片失败 uploadId={} chunkIndex={} mediaId={}", uploadId, i, mediaId, e);
            }
        }
        try {
            stringRedisTemplate.delete(List.of(uploadKey(uploadId), partsKey(uploadId)));
        } catch (RuntimeException e) {
            log.warn("删除分片上传状态失败 uploadId={} mediaId={}", uploadId, mediaId, e);
        }
    }

    private void removeCompletedFile(String fileUrl, RuntimeException originalError) {
        try {
            minioUtils.removeFile(fileUrl);
        } catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
            log.warn("回滚完整媒体文件失败 path={}", fileUrl, cleanupError);
        }
    }


    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }



    /**
     *创建filename
     * @param filename
     * @return
     */
    private String buildFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("视频文件名不能为空");
        }
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();//获取后缀前的最后一个文件名
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("视频文件名无效或过长");
        }
        String suffix = fileSuffix(normalized).toLowerCase(Locale.ROOT);//将后缀全部改为小写
        if (!VIDEO_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅支持 MP4、MOV、MKV、AVI、WEBM 和 M4V 视频");
        }
        return normalized;
    }

    private String uploadKey(String uploadId) {
        return UPLOAD_KEY_PREFIX + uploadId;
    }

    private String partsKey(String uploadId) {
        return uploadKey(uploadId) + ":parts";
    }

    private String chunkObjectName(String uploadId, int chunkIndex) {
        return "chunk-uploads/"+ uploadId + "/part-" + chunkIndex;//最终为 chunk-uploads/uploadId/part-chunkIndex
    }

    private Map<Object, Object> requireUpload(String uploadId, Long userId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("上传ID不能为空");
        }
        if (userId == null) {
            throw new IllegalArgumentException("用户不能为空");
        }

        Map<Object, Object> metadata = stringRedisTemplate.opsForHash().entries(uploadKey(uploadId));
        if (metadata.isEmpty()) {
            throw new IllegalArgumentException("上传任务不存在或已过期");
        }
        if (!Objects.equals(String.valueOf(userId), String.valueOf(metadata.get("userId")))) {
            throw new SecurityException("无权访问该上传任务");
        }
        return metadata;
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
    private String completedKey(String uploadId){
        return "upload:chunked:" + uploadId +":completed";
    }

}
