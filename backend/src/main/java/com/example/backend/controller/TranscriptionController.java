package com.example.backend.controller;

import com.example.backend.common.Result;
import com.example.backend.common.ErrorCode;
import com.example.backend.dto.TranscriptionTaskResponse;
import com.example.backend.entity.MediaFile;
import com.example.backend.exception.BusinessException;
import com.example.backend.service.MediaService;
import com.example.backend.service.TranscriptionService;
import jakarta.annotation.Resource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频转写任务接口。
 */
@RestController
@RequestMapping("/transcriptions")
public class TranscriptionController {

    @Resource
    private TranscriptionService transcriptionService;
    @Resource
    private MediaService mediaService;

    /**
     * 提交转写任务，后台异步执行。
     */
    @PostMapping
    public ResponseEntity<Result<Void>> create(
            @RequestParam Long mediaId,
            @RequestParam Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(mediaId, userId);
        if (!"COMPLETED".equals(mediaFile.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "视频尚未上传完成");
        }
        if (!transcriptionService.queue(mediaFile.getId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "视频转写任务正在处理中");
        }

        try {
            transcriptionService.transcribe(mediaFile.getId());
        } catch (TaskRejectedException e) {
            transcriptionService.rejectQueued(mediaFile.getId());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "转写任务队列已满或暂不可用，请稍后重试");
        }
        return ResponseEntity.accepted().body(Result.ok());
    }

    /**
     * 查询视频转写任务状态。
     */
    @GetMapping("/status")
    public Result<TranscriptionTaskResponse> status(
            @RequestParam Long mediaId,
            @RequestParam Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(mediaId, userId);
        return Result.ok(transcriptionService.status(mediaFile));
    }
}
