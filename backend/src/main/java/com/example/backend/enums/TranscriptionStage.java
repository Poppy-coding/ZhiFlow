package com.example.backend.enums;

/**
 * 执行过程的状态
 * 
 */
public enum TranscriptionStage {
    QUEUED,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    SAVING,
    COMPLETED,
    FAILED
}