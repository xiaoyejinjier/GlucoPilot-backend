package com.threadfit.glucopilot.ai.business.speech;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SpeechDtos {
    private SpeechDtos() { }

    public record Request(
            @NotBlank(message = "请先录制语音")
            @Size(max = 699052, message = "录音过大，请分段录制") String audioBase64,
            @NotBlank @Pattern(regexp = "mp3", message = "仅支持MP3录音") String format,
            @NotNull @Min(value = 500, message = "录音太短，请重新录制")
            @Max(value = 60000, message = "每段录音最多60秒") Integer durationMs) { }

    public record Result(String text) { }
}
