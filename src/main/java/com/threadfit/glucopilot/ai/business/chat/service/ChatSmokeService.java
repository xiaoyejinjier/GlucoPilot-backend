package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatSmokeDtos;
import com.threadfit.glucopilot.ai.config.SystemPromptProvider;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

/**
 * L0 冒烟：只做一次同步大模型调用，不落库、不流式。
 * 目的是一次性排掉「Key 无效 / 模型名错 / 提示词没加载 / 网络不通 / 超时」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSmokeService {

    private final ChatClient chatClient;
    private final SystemPromptProvider systemPromptProvider;

    public ChatSmokeDtos.SmokeResponse smoke(Long userId, String message) {
        long startNanos = System.nanoTime();
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .user(message)
                    .call()
                    .chatResponse();
        } catch (Exception ex) {
            log.error("大模型冒烟调用失败 userId={} message={}", userId, message, ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "调用大模型失败：" + rootCauseMessage(ex));
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        Generation generation = response == null ? null : response.getResult();

        ChatSmokeDtos.SmokeResponse result = ChatSmokeDtos.SmokeResponse.builder()
                .reply(textOf(generation))
                .model(metadata == null ? null : metadata.getModel())
                .latencyMs(latencyMs)
                .promptTokens(usage == null ? null : usage.getPromptTokens())
                .completionTokens(usage == null ? null : usage.getCompletionTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                .finishReason(finishReasonOf(generation))
                .systemPromptSource(systemPromptProvider.source())
                .systemPromptChars(systemPromptProvider.chars())
                .build();

        log.info("大模型冒烟成功 userId={} model={} latencyMs={} tokens={}/{} finish={}",
                userId, result.getModel(), latencyMs, result.getPromptTokens(),
                result.getCompletionTokens(), result.getFinishReason());
        return result;
    }

    private static String textOf(Generation generation) {
        if (generation == null || generation.getOutput() == null) {
            return null;
        }
        return generation.getOutput().getText();
    }

    private static String finishReasonOf(Generation generation) {
        if (generation == null || generation.getMetadata() == null) {
            return null;
        }
        return generation.getMetadata().getFinishReason();
    }

    /** 取最深层异常信息，截断后回显，便于在接口返回里直接看出是 Key 错还是模型名错。 */
    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
