package com.example.backend.dto;

import com.example.backend.enums.TranscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionTaskResponse {

    private TranscriptionStatus status;

    private String result;

    private String message;
}
