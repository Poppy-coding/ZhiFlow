package com.example.backend.controller;

import com.example.backend.common.Result;
import com.example.backend.dto.MediaUploadResponse;
import com.example.backend.service.MediaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/media")
public class MediaController {

    @Resource
    private MediaService mediaService;

    /**
     * 上传文件列表
     * @param file
     * @param userId
     * @return
     */
    @PostMapping("/upload")
    public Result<MediaUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam Long userId) {
        log.info("media_upload_request filename={} userId={}", file.getOriginalFilename(), userId);
        return Result.ok(mediaService.upload(file, userId));
    }

    /**
     * 查看上传文件列表
     * @return
     */
    @GetMapping("/list")
    public Result<List<MediaUploadResponse>> list(@RequestParam Long userId) {
        log.info("查看用户{}上传文件列表 ", userId);
        return Result.ok(mediaService.listByUser(userId));
    }

    /**
     * 获取视频播放地址
     * @param id
     * @param userId
     * @return
     */
    @GetMapping("/playback")
    public Result<String> playback(@RequestParam Long id, @RequestParam Long userId) {
        log.info("获取用户{}的视频{}播放地址", userId, id);
        return Result.ok(mediaService.playback(id, userId));
    }

    /**
     * 删除文件
     * @param id
     * @param userId
     * @return
     */
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam Long id, @RequestParam Long userId) {
        mediaService.delete(id, userId);
        return Result.ok();
    }
}
