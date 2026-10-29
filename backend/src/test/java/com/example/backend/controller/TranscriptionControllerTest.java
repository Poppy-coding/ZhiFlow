package com.example.backend.controller;

import com.example.backend.common.ErrorCode;
import com.example.backend.dto.TranscriptionTaskResponse;
import com.example.backend.entity.MediaFile;
import com.example.backend.service.MediaService;
import com.example.backend.service.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TranscriptionControllerTest {

    private final List<String> calls = new ArrayList<>();
    private final StubTranscriptionService transcription = new StubTranscriptionService();
    private MediaFile media;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setStatus("COMPLETED");

        MediaService mediaService = (MediaService) Proxy.newProxyInstance(
                MediaService.class.getClassLoader(), new Class<?>[]{MediaService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("requireOwnedMedia")) {
                        assertEquals(42L, args[0]);
                        assertEquals(7L, args[1]);
                        calls.add("ownership");
                        return media;
                    }
                    throw new UnsupportedOperationException(method.toString());
                });

        TranscriptionController controller = new TranscriptionController();
        ReflectionTestUtils.setField(controller, "mediaService", mediaService);
        ReflectionTestUtils.setField(controller, "transcriptionService", transcription);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void acceptsAfterOwnershipCheckQueueAndDispatch() throws Exception {
        submit().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(List.of("ownership", "queue:42", "transcribe:42"), calls);
    }

    @Test
    void duplicateSubmissionReturnsConflictWithoutDispatching() throws Exception {
        transcription.accepted = false;

        submit().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.code()));

        assertEquals(List.of("ownership", "queue:42"), calls);
    }

    @Test
    void rejectedDispatchRollsBackQueueAndReturnsServiceUnavailable() throws Exception {
        transcription.rejected = true;

        submit().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.SERVICE_UNAVAILABLE.code()));

        assertEquals(List.of("ownership", "queue:42", "transcribe:42", "reject:42"), calls);
    }

    @Test
    void unfinishedUploadReturnsConflictWithoutQueueing() throws Exception {
        media.setStatus("UPLOADING");

        submit().andExpect(status().isConflict());

        assertEquals(List.of("ownership"), calls);
    }

    private org.springframework.test.web.servlet.ResultActions submit() throws Exception {
        return mvc.perform(post("/transcriptions")
                .param("mediaId", "42")
                .param("userId", "7"));
    }

    private final class StubTranscriptionService implements TranscriptionService {
        boolean accepted = true;
        boolean rejected;

        @Override
        public boolean queue(Long mediaId) {
            calls.add("queue:" + mediaId);
            return accepted;
        }

        @Override
        public void transcribe(Long mediaId) {
            calls.add("transcribe:" + mediaId);
            if (rejected) {
                throw new TaskRejectedException("test executor is full");
            }
        }

        @Override
        public TranscriptionTaskResponse status(MediaFile mediaFile) {
            throw new UnsupportedOperationException("not used in submission tests");
        }

        @Override
        public void rejectQueued(Long mediaId) {
            calls.add("reject:" + mediaId);
        }
    }
}
