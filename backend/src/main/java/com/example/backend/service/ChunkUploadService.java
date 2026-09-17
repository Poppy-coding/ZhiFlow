package com.example.backend.service;

import com.example.backend.dto.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

public interface ChunkUploadService {

    /**
     * 初始化分片上传
     * @param filename
     * @param totalChunks
     * @param userId
     * @return
    */
    String initialize(String filename, int totalChunks, Long userId);

    /**
     * 查询已经上传成功的分片编号
     * @param uploadId 上传任务ID
     * @param userId 用户ID
     * @return 已上传的分片编号
     */
    Set<Integer> uploadChunks(String uploadId, Long userId);

    /**
     * 上传单个分片
     * @param uploadId
     * @param chunkIndex
     * @param totalChunks
     * @param chunk
     * @param userId
     */
    void uploadChunk(String uploadId, int chunkIndex, int totalChunks, MultipartFile chunk, Long userId);

    /**
     * 实现完整的分片上传
     * @param uploadId
     * @param userId
     * @return
     */
    MediaUploadResponse completeUpload(String uploadId, Long userId);
}
