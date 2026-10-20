package com.threadfit.glucopilot.ai.business.speech;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** 仅转写短录音，不执行聊天、工具调用或媒体持久化。 */
@Service
public class SpeechTranscriptionService {
    private static final int MAX_AUDIO_BYTES = 512 * 1024;
    private final SpeechProperties properties;
    private final AIProperties aiProperties;
    private final RestClient client;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(8);

    @Autowired
    public SpeechTranscriptionService(SpeechProperties properties, AIProperties aiProperties) {
        this(properties, aiProperties, createClient());
    }

    SpeechTranscriptionService(SpeechProperties properties, AIProperties aiProperties, RestClient client) {
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.client = client;
    }

    private static RestClient createClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(45));
        return RestClient.builder().requestFactory(factory).build();
    }

    public SpeechDtos.Result transcribe(Long userId, SpeechDtos.Request request) {
        if (userId == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        validateAudio(request);
        String key = properties.getApiKey();
        if (key == null || key.isBlank()) key = aiProperties.getVision().getApiKey();
        if (!properties.isEnabled() || key == null || key.isBlank() || key.contains("${")) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "语音识别尚未配置，请配置百炼语音密钥后重试");
        }
        if (!inFlight.add(userId)) throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "正在识别上一段语音，请稍候");
        boolean acquired = capacity.tryAcquire();
        try {
            if (!acquired) throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "语音识别繁忙，请稍后重试");
            var body = Map.of(
                    "model", properties.getModel(), "stream", false,
                    "messages", List.of(Map.of("role", "user", "content", List.of(Map.of(
                            "type", "input_audio", "input_audio", Map.of("data", "data:audio/mpeg;base64," + request.audioBase64()))))),
                    "asr_options", Map.of("language", "zh", "enable_itn", true));
            String endpoint = properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            var response = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key).body(body)
                    .retrieve().body(JsonNode.class);
            String text = response == null ? "" : response.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) throw new BizException(ErrorCode.BIZ_REJECTED, "没有听清，请靠近麦克风重新说一次");
            if (text.length() > 2000) throw new BizException(ErrorCode.BIZ_REJECTED, "语音内容过长，请分段录制");
            return new SpeechDtos.Result(text);
        } catch (BizException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            // 不透传上游body或异常文本，其中可能含请求音频和服务端配置。
            if (ex.getStatusCode().value() == 429) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "语音识别繁忙，请稍后重试");
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR, "语音识别调用失败，请检查语音模型和API配置后重试");
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "语音识别连接失败或超时，请稍后重试");
        } finally {
            if (acquired) capacity.release();
            inFlight.remove(userId);
        }
    }

    private void validateAudio(SpeechDtos.Request request) {
        if (request == null || !"mp3".equals(request.format()) || request.durationMs() == null ||
                request.durationMs() < 500 || request.durationMs() > 60000 || request.audioBase64() == null ||
                request.audioBase64().length() > 699052) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请提交60秒内的MP3录音");
        }
        byte[] audio;
        try { audio = Base64.getDecoder().decode(request.audioBase64()); }
        catch (IllegalArgumentException ex) { throw new BizException(ErrorCode.BAD_REQUEST, "录音数据格式错误，请重新录制"); }
        boolean mp3 = audio.length >= 4 && ((audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') ||
                ((audio[0] & 0xff) == 0xff && (audio[1] & 0xe0) == 0xe0));
        if (audio.length > MAX_AUDIO_BYTES || !mp3) throw new BizException(ErrorCode.BAD_REQUEST, "录音过大或格式不正确，请重新录制");
    }
}
