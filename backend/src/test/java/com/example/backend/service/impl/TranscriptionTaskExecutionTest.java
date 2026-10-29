package com.example.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.backend.config.AsyncConfig;
import com.example.backend.entity.MediaFile;
import com.example.backend.mapper.MediaFileMapper;
import com.example.backend.service.TranscriptionService;
import com.example.backend.utils.FfmpegUtils;
import com.example.backend.utils.MinioUtils;
import com.example.backend.utils.SiliconFlowAsrUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptionTaskExecutionTest {

    private static final long MEDIA_ID = 42L;
    private static final String ACTIVE_KEY = "zhiflow:transcription:active:" + MEDIA_ID;
    private static final String STATE_KEY = "zhiflow:transcription:state:" + MEDIA_ID;
    private static final String TRANSCRIPT = "识别出的文字";

    private Dependencies dependencies;

    @BeforeEach
    void setUp() {
        dependencies = new Dependencies();
    }

    @Test
    void savesOnlyTranscriptBeforeCompletingAndClearsActiveTask() {
        dependencies.service.transcribe(MEDIA_ID);

        assertEquals(List.of("read-media", "state:PROCESSING", "resolve-source", "extract",
                "asr", "update", "state:COMPLETED", "delete-active"), dependencies.events);
        assertTranscriptUpdate();
        assertEquals(new StateWrite(STATE_KEY, "PROCESSING", Duration.ofHours(2)),
                dependencies.redis.states.get(0));
        assertEquals(new StateWrite(STATE_KEY, "COMPLETED", Duration.ofDays(7)),
                dependencies.redis.states.get(1));
        assertEquals(List.of(ACTIVE_KEY), dependencies.redis.deletedKeys);
        assertFalse(Files.exists(dependencies.workingDirectory));
    }

    @Test
    void recordsFailureWithoutSavingWhenAsrFails() {
        dependencies.asrFailure = new IllegalStateException("ASR unavailable");

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertFailedAndReleased();
        assertEquals(0, dependencies.updateCalls);
        assertFalse(Files.exists(dependencies.workingDirectory));
    }

    @Test
    void recordsFailureAndReleasesTaskWhenMediaIsMissing() {
        dependencies.media = null;

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertFailedAndReleased(false);
        assertEquals(0, dependencies.updateCalls);
        assertFalse(dependencies.events.contains("resolve-source"));
        assertNull(dependencies.workingDirectory);
    }

    @Test
    void doesNotStartExtractionWhenUploadIsIncomplete() {
        dependencies.media.setStatus("UPLOADING");

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertFailedAndReleased(false);
        assertFalse(dependencies.events.contains("extract"));
        assertEquals(0, dependencies.updateCalls);
    }

    @Test
    void recordsFailureWhenTranscriptUpdateFindsNoRow() {
        dependencies.updateRows = 0;

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertFailedAndReleased();
        assertEquals(1, dependencies.updateCalls);
    }

    @Test
    void recordsFailureWhenDatabaseUpdateThrows() {
        dependencies.updateFailure = new IllegalStateException("database unavailable");

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertFailedAndReleased();
        assertEquals(1, dependencies.updateCalls);
    }

    @Test
    void redisStateFailuresDoNotPreventSavingOrTurnSavedResultIntoFailure() {
        dependencies.redis.failedStates.add("PROCESSING");
        dependencies.redis.failedStates.add("COMPLETED");

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertTranscriptUpdate();
        assertEquals(List.of("PROCESSING", "COMPLETED"), dependencies.redis.stateNames());
        assertEquals(List.of(ACTIVE_KEY), dependencies.redis.deletedKeys);
    }

    @Test
    void redisDeleteFailureDoesNotTurnSavedResultIntoFailure() {
        dependencies.redis.failDelete = true;

        assertDoesNotThrow(() -> dependencies.service.transcribe(MEDIA_ID));

        assertTranscriptUpdate();
        assertEquals(List.of("PROCESSING", "COMPLETED"), dependencies.redis.stateNames());
        assertEquals(List.of(ACTIVE_KEY), dependencies.redis.deletedKeys);
    }

    @Test
    void rejectedTaskRecordsFailureBeforeClearingActiveKey() {
        dependencies.service.rejectQueued(MEDIA_ID);

        assertEquals(List.of("state:FAILED", "delete-active"), dependencies.events);
        assertEquals(List.of(new StateWrite(STATE_KEY, "FAILED", Duration.ofMinutes(10))),
                dependencies.redis.states);
        assertEquals(List.of(ACTIVE_KEY), dependencies.redis.deletedKeys);
    }

    @Test
    void rejectedTaskStillAttemptsCleanupWhenRedisStateWriteFails() {
        dependencies.redis.failedStates.add("FAILED");

        assertDoesNotThrow(() -> dependencies.service.rejectQueued(MEDIA_ID));

        assertEquals(List.of("state:FAILED", "delete-active"), dependencies.events);
        assertEquals(List.of(ACTIVE_KEY), dependencies.redis.deletedKeys);
    }

    @Test
    void springProxyRunsTranscriptionOnConfiguredExecutor() throws InterruptedException {
        Thread requestThread = Thread.currentThread();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class);
            context.getBeanFactory().registerSingleton("redisTemplate", new RedisTemplate<>());
            context.getBeanFactory().registerSingleton("stringRedisTemplate", dependencies.redis);
            context.getBeanFactory().registerSingleton("mediaFileMapper", dependencies.mapper);
            context.getBeanFactory().registerSingleton("minioUtils", dependencies.minio);
            context.getBeanFactory().registerSingleton("ffmpegUtils", dependencies.ffmpeg);
            context.getBeanFactory().registerSingleton("siliconFlowAsrUtils", dependencies.asr);
            context.registerBean("transcriptionService", TranscriptionServiceImpl.class,
                    () -> dependencies.service);
            context.refresh();

            TranscriptionService service = context.getBean(TranscriptionService.class);
            assertTrue(AopUtils.isAopProxy(service));
            service.transcribe(MEDIA_ID);

            assertTrue(dependencies.redis.finished.await(5, TimeUnit.SECONDS),
                    "background task did not finish");
            assertNotNull(dependencies.executionThread);
            assertNotSame(requestThread, dependencies.executionThread);
            assertTrue(dependencies.executionThread.getName().startsWith("Transcription-"));
            assertTranscriptUpdate();
            assertEquals(List.of("PROCESSING", "COMPLETED"), dependencies.redis.stateNames());
        }
    }

    private void assertTranscriptUpdate() {
        assertEquals(1, dependencies.updateCalls);
        assertNull(dependencies.updatedEntity, "must not write the previously loaded entity");
        assertNotNull(dependencies.updateWrapper);
        String setClause = dependencies.updateWrapper.getSqlSet();
        String whereClause = dependencies.updateWrapper.getSqlSegment();
        assertTrue(setClause.startsWith("transcript_text="));
        assertFalse(setClause.contains(","), "only transcript_text may be updated");
        assertTrue(whereClause.contains("id ="));
        assertEquals(Set.of(TRANSCRIPT, MEDIA_ID),
                new HashSet<>(dependencies.updateWrapper.getParamNameValuePairs().values()));
        assertEquals("unchanged filename.mp4", dependencies.media.getFilename());
    }

    private void assertFailedAndReleased() {
        assertFailedAndReleased(true);
    }

    private void assertFailedAndReleased(boolean processingStarted) {
        assertEquals(processingStarted ? List.of("PROCESSING", "FAILED") : List.of("FAILED"),
                dependencies.redis.stateNames());
        assertEquals(new StateWrite(STATE_KEY, "FAILED", Duration.ofHours(1)),
                dependencies.redis.states.get(processingStarted ? 1 : 0));
        assertEquals(List.of(ACTIVE_KEY), dependencies.redis.deletedKeys);
        assertEquals("delete-active", dependencies.events.get(dependencies.events.size() - 1));
    }

    private record StateWrite(String key, String value, Duration ttl) {}

    /** Plain substitutes keep these tests independent of external services and Mockito agents. */
    private static final class Dependencies {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final RecordingRedis redis = new RecordingRedis(events);
        final TranscriptionServiceImpl service = new TranscriptionServiceImpl();
        MediaFile media = media();
        int updateRows = 1;
        int updateCalls;
        Object updatedEntity;
        UpdateWrapper<?> updateWrapper;
        RuntimeException updateFailure;
        RuntimeException asrFailure;
        Path workingDirectory;
        Thread executionThread;

        final MediaFileMapper mapper = (MediaFileMapper) Proxy.newProxyInstance(
                MediaFileMapper.class.getClassLoader(), new Class<?>[]{MediaFileMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("selectById")) {
                        assertEquals(MEDIA_ID, args[0]);
                        events.add("read-media");
                        return media;
                    }
                    if (method.getName().equals("update") && args.length == 2) {
                        events.add("update");
                        updateCalls++;
                        updatedEntity = args[0];
                        updateWrapper = (UpdateWrapper<?>) args[1];
                        if (updateFailure != null) {
                            throw updateFailure;
                        }
                        return updateRows;
                    }
                    throw new UnsupportedOperationException(method.toString());
                });

        final MinioUtils minio = new MinioUtils() {
            @Override
            public String readableSource(String source) {
                assertEquals("stored-video.mp4", source);
                events.add("resolve-source");
                return "readable-video.mp4";
            }
        };

        final FfmpegUtils ffmpeg = new FfmpegUtils() {
            @Override
            public List<Path> extractAudioSegments(String videoSource, Path directory) {
                assertEquals("readable-video.mp4", videoSource);
                events.add("extract");
                executionThread = Thread.currentThread();
                workingDirectory = directory;
                try {
                    return List.of(Files.writeString(directory.resolve("audio_000000.mp3"), "audio"));
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }
        };

        final SiliconFlowAsrUtils asr = new SiliconFlowAsrUtils() {
            @Override
            public String audioToText(Path audioPath) {
                events.add("asr");
                if (asrFailure != null) {
                    throw asrFailure;
                }
                return TRANSCRIPT;
            }
        };

        Dependencies() {
            ReflectionTestUtils.setField(service, "redisTemplate", redis);
            ReflectionTestUtils.setField(service, "mediaFileMapper", mapper);
            ReflectionTestUtils.setField(service, "minioUtils", minio);
            ReflectionTestUtils.setField(service, "ffmpegUtils", ffmpeg);
            ReflectionTestUtils.setField(service, "siliconFlowAsrUtils", asr);
        }

        private static MediaFile media() {
            MediaFile file = new MediaFile();
            file.setId(MEDIA_ID);
            file.setUserId(7L);
            file.setStatus("COMPLETED");
            file.setFilePath("stored-video.mp4");
            file.setFilename("unchanged filename.mp4");
            return file;
        }
    }

    private static final class RecordingRedis extends StringRedisTemplate {
        final List<StateWrite> states = Collections.synchronizedList(new ArrayList<>());
        final List<String> deletedKeys = Collections.synchronizedList(new ArrayList<>());
        final Set<String> failedStates = new HashSet<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final List<String> events;
        boolean failDelete;
        final ValueOperations<String, String> operations;

        @SuppressWarnings("unchecked")
        RecordingRedis(List<String> events) {
            this.events = events;
            operations = (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(), new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("set") && args.length == 3
                                && args[2] instanceof Duration ttl) {
                            String key = (String) args[0];
                            String value = (String) args[1];
                            events.add("state:" + value);
                            states.add(new StateWrite(key, value, ttl));
                            if (failedStates.contains(value)) {
                                throw new IllegalStateException("Redis write unavailable");
                            }
                            return null;
                        }
                        throw new UnsupportedOperationException(method.toString());
                    });
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return operations;
        }

        @Override
        public Boolean delete(String key) {
            events.add("delete-active");
            deletedKeys.add(key);
            finished.countDown();
            if (failDelete) {
                throw new IllegalStateException("Redis delete unavailable");
            }
            return true;
        }

        List<String> stateNames() {
            return states.stream().map(StateWrite::value).toList();
        }
    }
}
