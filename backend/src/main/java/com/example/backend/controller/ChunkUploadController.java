package com.example.backend.controller;

import com.example.backend.common.Result;
import com.example.backend.dto.MediaUploadResponse;
import com.example.backend.service.ChunkUploadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/media")
public class ChunkUploadController {

    @Resource
    private ChunkUploadService chunkUploadService;

    /**
     * 初始化分片上传
     * @param filename
     * @param totalChunks
     * @param userId
     * @return
     */
    @PostMapping("/init-upload")
    public Result<String> initUpload(@RequestParam String filename,
                                     @RequestParam int totalChunks,
                                     @RequestParam Long userId) {
        log.info("分块初始化 filename={} totalChunks={} userId={}", filename, totalChunks, userId);
        return Result.ok(chunkUploadService.initialize(filename, totalChunks, userId));
    }

    /**
     * 查询所有上传的块
     * @param uploadId
     * @param userId
     * @return
     */
    @GetMapping("/upload-status")
    public Result<Set<Integer>> uploadChunks(@RequestParam String uploadId,
                                             @RequestParam Long userId){
        log.info("查询所有的分块 uploadId : {}, userId : {}", uploadId, userId);
        return  Result.ok(chunkUploadService.uploadChunks(uploadId, userId));
    }

    /**
     * 实现单片上传
     * @param uploadId
     * @param chunkIndex
     * @param totalChunks
     * @param chunk
     * @param userId
     * @return
     */
    @PostMapping("/upload-chunk")
    public Result<Void> uploadChunk(@RequestParam String uploadId,
                                    @RequestParam int chunkIndex,
                                    @RequestParam int totalChunks,
                                    @RequestParam("chunk") MultipartFile chunk,
                                    @RequestParam Long userId) {
        chunkUploadService.uploadChunk(
                uploadId, chunkIndex, totalChunks, chunk, userId);
        return Result.ok();
    }

    /**
     * 合并分片并完成上传
     * @param uploadId 上传任务ID
     * @param userId 用户ID
     * @return 上传完成的媒体信息
     */
    @PostMapping("/complete-upload")
    public Result<MediaUploadResponse> completeUpload(@RequestParam String uploadId,
                                                      @RequestParam Long userId) {
        log.info("完成分片上传 uploadId={} userId={}", uploadId, userId);
        return Result.ok(chunkUploadService.completeUpload(uploadId, userId));
    }
}
