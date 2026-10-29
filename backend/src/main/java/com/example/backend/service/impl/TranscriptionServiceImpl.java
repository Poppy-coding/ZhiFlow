package com.example.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.backend.common.ErrorCode;
import com.example.backend.dto.TranscriptionTaskResponse;
import com.example.backend.entity.MediaFile;
import com.example.backend.enums.TranscriptionStatus;
import com.example.backend.exception.BusinessException;
import com.example.backend.mapper.MediaFileMapper;
import com.example.backend.service.TranscriptionService;
import com.example.backend.utils.FfmpegUtils;
import com.example.backend.utils.MinioUtils;
import com.example.backend.utils.SiliconFlowAsrUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

@Slf4j
@Service
public class TranscriptionServiceImpl implements TranscriptionService {

    private static final String TRANSCRIPTION_ACTIVE_KEY = "zhiflow:transcription:active:";
    private static final String TRANSCRIPTION_STATE_KEY = "zhiflow:transcription:state:";
    private static final Duration ACTIVE_TTL = Duration.ofHours(2);
    private static final Duration COMPLETED_TTL = Duration.ofDays(7);
    private static final Duration FAILED_TTL = Duration.ofHours(1);
    private static final Duration REJECTED_TTL = Duration.ofMinutes(10);

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private MediaFileMapper mediaFileMapper;

    @Resource
    private MinioUtils minioUtils;

    @Resource
    private FfmpegUtils ffmpegUtils;

    @Resource
    private SiliconFlowAsrUtils siliconFlowAsrUtils;

    /**
     * 将转写任务放入队列，并使用 Redis SETNX 防止同一媒体被重复处理。
     *
     * @param mediaId 媒体文件 ID
     * @return 是否成功加入队列
     */
    @Override
    public boolean queue(Long mediaId) {
        if (mediaId == null) {
            throw new IllegalArgumentException("媒体ID不能为空");
        }

        String activeKey = createActiveKey(mediaId);
        String stateKey = createStateKey(mediaId);
        Boolean accepted;
        try {
            //不存在则创建成功 ： set ifAbsent
            accepted = redisTemplate.opsForValue().setIfAbsent(activeKey, "1", ACTIVE_TTL);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "转写任务队列暂时不可用");
        }

        if (Boolean.FALSE.equals(accepted)) {
            return false;
        }
        if (!Boolean.TRUE.equals(accepted)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "转写任务队列状态异常");
        }

        try {
            redisTemplate.opsForValue().set(stateKey, TranscriptionStatus.QUEUED.name(), ACTIVE_TTL);//放入状态
            return true;
        } catch (RuntimeException e) {
            rollbackQueuedTask(activeKey, stateKey, mediaId);//回滚
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "转写任务状态保存失败");
        }
    }

    /**
     * 在线程池中执行转写，成功后保存文字，结束后释放任务占用。
     */
    @Override
    @Async("transcriptionExecutor")
    public void transcribe(Long mediaId){
        if(mediaId == null){
            throw new IllegalArgumentException("媒体ID不能为空");
        }

        try {
            //任务可能等待过一段时间，执行前重新查询媒体
            MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
            if(mediaFile == null){
                throw new NoSuchElementException("媒体文件不存在");
            }
            if(!"COMPLETED".equals(mediaFile.getStatus())){
                throw new IllegalStateException("视频尚未上传完成");
            }

            setState(mediaId, TranscriptionStatus.PROCESSING, ACTIVE_TTL);
            String videoSource = minioUtils.readableSource(mediaFile.getFilePath());
            String text = transcribeToText(videoSource);

            if(Thread.currentThread().isInterrupted()){
                throw new IllegalStateException("视频转写已被中断");
            }

            //只更新转写文字，避免覆盖媒体的其他字段
            int updated = mediaFileMapper.update(null, new UpdateWrapper<MediaFile>()
                    .eq("id", mediaId)
                    .set("transcript_text", text));
            if(updated != 1){
                throw new IllegalStateException("转写结果保存失败，媒体文件可能已被删除");
            }

            //先确认数据库保存成功，再记录完成状态
            setState(mediaId, TranscriptionStatus.COMPLETED, COMPLETED_TTL);
            log.info("视频转写完成 mediaId={}", mediaId);
        }catch(Exception e){
            setState(mediaId, TranscriptionStatus.FAILED, FAILED_TTL);
            log.error("视频转写失败 mediaId={}", mediaId, e);
        }finally {
            clearActive(mediaId);
        }
    }

    /**
     * 查询媒体文件的转写状态
     * @param mediaFile 媒体文件
     * @return
     */
    @Override
    public TranscriptionTaskResponse status(MediaFile mediaFile) {
        if (mediaFile == null || mediaFile.getId() == null) {
            throw new IllegalArgumentException("媒体文件不能为空");
        }

        String transcriptText = mediaFile.getTranscriptText();//获取转写文本
        if (transcriptText != null && !transcriptText.isBlank()) {
            return response(TranscriptionStatus.COMPLETED, transcriptText, "任务完成");
        }

        Long mediaId = mediaFile.getId();
        try {
            String stateValue = redisTemplate.opsForValue().get(createStateKey(mediaId));
            if (stateValue != null) {
                try {
                    //根据状态返回响应
                    return statusFor(TranscriptionStatus.valueOf(stateValue), mediaFile);
                } catch (IllegalArgumentException e) {
                    log.warn("invalid_transcription_state mediaId={} state={}", mediaId, stateValue);
                }
            }

            boolean active = Boolean.TRUE.equals(redisTemplate.hasKey(createActiveKey(mediaId)));//判断是否在处理
            return active
                    ? response(TranscriptionStatus.PROCESSING, null, "正在进行视频转写")
                    : response(TranscriptionStatus.NOT_STARTED, null, "尚未提交视频转写任务");//根据active来决定返回的值
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "转写任务状态暂时不可用");
        }
    }

    /**
     * 线程池拒绝执行
     * @param mediaId
     */
    @Override
    public void rejectQueued(Long mediaId) {
        if(mediaId == null){
            throw new IllegalArgumentException("媒体ID不能为空");
        }
        //先记录失败，再释放占用，避免覆盖后续新任务的排队状态
        setState(mediaId, TranscriptionStatus.FAILED, REJECTED_TTL);
        clearActive(mediaId);
    }

    /**
     * 提取音频分段，按顺序识别并合并成文字。
     * @param videoSource 本地视频路径或MinIO临时访问地址
     * @return 成功识别的分段文字，以换行符分隔
     */
    private String transcribeToText(String videoSource){
        if(videoSource == null || videoSource.isBlank()){
            throw new IllegalArgumentException("视频地址不能为空");
        }

        //声明在try外面，finally中才能访问
        Path workingDirectory = null;
        try {
            workingDirectory = Files.createTempDirectory("transcription-");
            List<Path> segments = ffmpegUtils.extractAudioSegments(
                    videoSource, workingDirectory);

            List<String> texts = new ArrayList<>();
            RuntimeException lastError = null;
            for(Path segment : segments){
                if(Thread.currentThread().isInterrupted()){
                    throw new IllegalStateException("视频转写已被中断");
                }
                try {
                    String text = siliconFlowAsrUtils.audioToText(segment);
                    if(Thread.currentThread().isInterrupted()){
                        throw new IllegalStateException("视频转写已被中断");
                    }
                    if(text != null && !text.isBlank()){
                        texts.add(text);
                    }
                }catch(RuntimeException e){
                    if(Thread.currentThread().isInterrupted()){
                        throw e;
                    }
                    //单段失败，记录后继续处理下一段
                    lastError = e;
                    log.warn("音频分段识别失败：{}", segment.getFileName(), e);
                }
            }

            if(texts.isEmpty()){
                throw new IllegalStateException(
                        "所有音频分段均未识别出有效文字", lastError);
            }
            return String.join("\n", texts);

        }catch(IOException e){
            throw new IllegalStateException("创建转写临时目录失败", e);
        }finally {
            //无论成功还是失败，都尝试清理本次任务的临时目录
            if(workingDirectory != null){
                try(Stream<Path> paths = Files.walk(workingDirectory)){
                    //先删除内部文件，最后删除目录
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        }catch(Exception e){
                            log.warn("临时文件清理失败：{}", path, e);
                        }
                    });
                }catch(Exception e){
                    //清理异常不覆盖转写结果或原始异常
                    log.warn("临时目录清理失败：{}", workingDirectory, e);
                }
            }
        }
    }

    /**
     * 根据转写状态返回响应
     * @param status
     * @param mediaFile
     * @return
     */
    private TranscriptionTaskResponse statusFor(TranscriptionStatus status, MediaFile mediaFile) {
        return switch (status) {
            case NOT_STARTED -> response(status, null, "尚未提交视频转写任务");
            case QUEUED -> response(status, null, "视频转写任务已排队");
            case PROCESSING -> response(status, null, "正在进行视频转写");
            case COMPLETED -> response(status, mediaFile.getTranscriptText(), "任务完成");
            case FAILED -> response(status, null, "视频转写失败，请稍后重试");
        };
    }

    /**
     * 创建返回响应
     * @param status
     * @param result
     * @param message
     * @return
     */
    private TranscriptionTaskResponse response(
            TranscriptionStatus status,
            String result,
            String message) {
        return TranscriptionTaskResponse.builder()
                .status(status)
                .result(result)
                .message(message)
                .build();
    }

    /**
     * 用于并发控制
     * @param mediaId
     * @return
     */
    private String createActiveKey(Long mediaId) {
        return TRANSCRIPTION_ACTIVE_KEY + mediaId;
    }

    /**
     * 创建状态key
     * @param mediaId
     * @return
     */
    private String createStateKey(Long mediaId) {
        return TRANSCRIPTION_STATE_KEY + mediaId;
    }

    /**
     * 更新执行状态，Redis异常只记录日志，不影响已保存的转写结果。
     */
    private void setState(Long mediaId, TranscriptionStatus status, Duration ttl){
        try {
            redisTemplate.opsForValue().set(createStateKey(mediaId), status.name(), ttl);
        }catch(RuntimeException e){
            log.warn("转写任务状态更新失败 mediaId={} status={}", mediaId, status, e);
        }
    }

    /**
     * 释放任务占用；Redis不可用时，由原有TTL兜底过期。
     */
    private void clearActive(Long mediaId){
        try {
            redisTemplate.delete(createActiveKey(mediaId));
        }catch(RuntimeException e){
            log.warn("转写任务占用释放失败 mediaId={}", mediaId, e);
        }
    }

    private void rollbackQueuedTask(String activeKey, String stateKey, Long mediaId) {
        try {
            redisTemplate.delete(activeKey);
            redisTemplate.delete(stateKey);
        } catch (RuntimeException cleanupError) {
            log.warn("transcription_queue_rollback_failed mediaId={}", mediaId, cleanupError);
        }
    }
}
