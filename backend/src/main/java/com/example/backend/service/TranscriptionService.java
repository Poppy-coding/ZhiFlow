package com.example.backend.service;

import com.example.backend.dto.TranscriptionTaskResponse;
import com.example.backend.entity.MediaFile;

/**
 * 视频转写任务业务接口。
 */
public interface TranscriptionService {

    /**
     * 将媒体转写任务加入队列。同一媒体已经存在活动任务时返回 {@code false}。
     *
     * @param mediaId 媒体文件 ID
     * @return 是否成功加入队列
     */
    boolean queue(Long mediaId);

    /**
     * 异步执行媒体转写，保存文字并更新任务状态。
     * @param mediaId 媒体文件 ID
     */
    void transcribe(Long mediaId);

    /**
     * 查询媒体文件的转写状态。
     *
     * @param mediaFile 媒体文件
     * @return 当前转写状态和结果
     */
    TranscriptionTaskResponse status(MediaFile mediaFile);

    /**
     * 线程池拒绝任务时，记录失败并释放占用。
     * @param mediaId
     */
    void rejectQueued(Long mediaId);
}
