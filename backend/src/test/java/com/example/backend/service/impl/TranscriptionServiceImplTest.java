package com.example.backend.service.impl;

import com.example.backend.utils.FfmpegUtils;
import com.example.backend.utils.SiliconFlowAsrUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptionServiceImplTest {

    private TranscriptionServiceImpl service;
    private StubFfmpegUtils ffmpegUtils;
    private StubAsrUtils asrUtils;

    @BeforeEach
    void setUp() {
        service = new TranscriptionServiceImpl();
        ffmpegUtils = new StubFfmpegUtils();
        asrUtils = new StubAsrUtils();
        ReflectionTestUtils.setField(service, "ffmpegUtils", ffmpegUtils);
        ReflectionTestUtils.setField(service, "siliconFlowAsrUtils", asrUtils);
    }

    @Test
    void joinsSegmentsInOrderAndDeletesTemporaryFiles() {
        assertEquals("第一段\n第二段", transcribe());
        assertEquals(List.of(ffmpegUtils.firstSegment, ffmpegUtils.secondSegment), asrUtils.calls);
        assertFalse(Files.exists(ffmpegUtils.workingDirectory));
    }

    @Test
    void continuesAfterOneSegmentFailsAndDeletesTemporaryFiles() {
        asrUtils.response = path -> {
            if (path.equals(ffmpegUtils.firstSegment)) {
                throw new IllegalStateException("第一段识别失败");
            }
            return "第二段";
        };

        assertEquals("第二段", transcribe());
        assertEquals(List.of(ffmpegUtils.firstSegment, ffmpegUtils.secondSegment), asrUtils.calls);
        assertFalse(Files.exists(ffmpegUtils.workingDirectory));
    }

    @Test
    void preservesLastErrorWhenAllSegmentsFailAndDeletesTemporaryFiles() {
        RuntimeException firstError = new IllegalStateException("第一段识别失败");
        RuntimeException lastError = new IllegalStateException("第二段识别失败");
        asrUtils.response = path -> {
            throw path.equals(ffmpegUtils.firstSegment) ? firstError : lastError;
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transcribe());

        assertSame(lastError, error.getCause());
        assertFalse(Files.exists(ffmpegUtils.workingDirectory));
    }

    @Test
    void deletesPartialFilesWhenAudioExtractionFails() {
        RuntimeException extractionError = new IllegalStateException("音频提取失败");
        ffmpegUtils.error = extractionError;

        assertSame(extractionError, assertThrows(IllegalStateException.class,
                () -> transcribe()));
        assertTrue(asrUtils.calls.isEmpty());
        assertFalse(Files.exists(ffmpegUtils.workingDirectory));
    }

    @Test
    void stopsOnInterruptionAndDeletesTemporaryFiles() {
        RuntimeException interruption = new IllegalStateException("ASR调用已被中断");
        asrUtils.response = path -> {
            Thread.currentThread().interrupt();
            throw interruption;
        };

        try {
            assertSame(interruption, assertThrows(IllegalStateException.class,
                    () -> transcribe()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of(ffmpegUtils.firstSegment), asrUtils.calls);
            assertFalse(Files.exists(ffmpegUtils.workingDirectory));
        } finally {
            Thread.interrupted();
        }
    }

    private String transcribe() {
        return ReflectionTestUtils.invokeMethod(
                service, "transcribeToText", "test-video.mp4");
    }

    private static class StubFfmpegUtils extends FfmpegUtils {

        private Path workingDirectory;
        private Path firstSegment;
        private Path secondSegment;
        private RuntimeException error;

        @Override
        public List<Path> extractAudioSegments(String videoSource, Path directory) {
            workingDirectory = directory;
            try {
                firstSegment = Files.writeString(
                        directory.resolve("audio_000000.mp3"), "first audio");
                secondSegment = Files.writeString(
                        directory.resolve("audio_000001.mp3"), "second audio");
            } catch (IOException e) {
                throw new IllegalStateException("测试音频创建失败", e);
            }
            if (error != null) {
                throw error;
            }
            return List.of(firstSegment, secondSegment);
        }
    }

    private static class StubAsrUtils extends SiliconFlowAsrUtils {

        private final List<Path> calls = new ArrayList<>();
        private Function<Path, String> response = path ->
                path.getFileName().toString().equals("audio_000000.mp3") ? "第一段" : "第二段";

        @Override
        public String audioToText(Path audioPath) {
            calls.add(audioPath);
            return response.apply(audioPath);
        }
    }
}
