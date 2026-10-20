package com.threadfit.glucopilot.ai.business.speech;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SpeechTranscriptionTest {
    private final SpeechProperties properties = new SpeechProperties();
    private final AIProperties ai = new AIProperties();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SpeechTranscriptionService service = new SpeechTranscriptionService(properties, ai, builder.build());
    private final SpeechDtos.Request request = new SpeechDtos.Request("SUQzBA==", "mp3", 2000);
    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String RESULT = "{\"choices\":[{\"message\":{\"content\":\"空腹血糖5.2\"}}]}";

    SpeechTranscriptionTest() { ai.getVision().setApiKey("test-vision-key"); }
    @AfterEach void clearContext() { RequestContext.clear(); }

    @Test void transcribesActualAudioAndUsesVisionCredentialFallback() {
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-vision-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:audio/mpeg;base64,SUQzBA==")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("qwen3-asr-flash")))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));
        assertEquals("空腹血糖5.2", service.transcribe(1L, request).text());
        server.verify();
    }

    @Test void explicitSpeechKeyAndEndpointOverrideVisionSettings() {
        properties.setApiKey("test-speech-key");
        properties.setBaseUrl("https://example.invalid/v1/");
        server.expect(requestTo("https://example.invalid/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-speech-key"))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));
        assertEquals("空腹血糖5.2", service.transcribe(1L, request).text());
        server.verify();
    }

    @Test void missingCredentialsAndDisabledServiceDoNotCallProvider() {
        ai.getVision().setApiKey("");
        assertEquals(ErrorCode.INTERNAL_ERROR, assertThrows(BizException.class, () -> service.transcribe(1L, request)).getErrorCode());
        properties.setApiKey("test-key"); properties.setEnabled(false);
        assertThrows(BizException.class, () -> service.transcribe(1L, request));
        server.verify();
    }

    @Test void rejectsInvalidBase64HeadersFormatsAndDurationsBeforeNetwork() {
        for (var invalid : new SpeechDtos.Request[] {
                new SpeechDtos.Request("not base64!", "mp3", 1000),
                new SpeechDtos.Request("SUQzBA==", "wav", 1000),
                new SpeechDtos.Request("SUQzBA==", "mp3", 499),
                new SpeechDtos.Request("SUQzBA==", "mp3", 60001),
                new SpeechDtos.Request("YWJjZA==", "mp3", 1000),
                new SpeechDtos.Request("", "mp3", 1000) }) {
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class, () -> service.transcribe(1L, invalid)).getErrorCode());
        }
        byte[] large = new byte[512 * 1024 + 1]; large[0] = 'I'; large[1] = 'D'; large[2] = '3';
        var oversized = new SpeechDtos.Request(Base64.getEncoder().encodeToString(large), "mp3", 1000);
        assertThrows(BizException.class, () -> service.transcribe(1L, oversized));
        server.verify();
    }

    @Test void blankTranscriptionIsAnErrorInsteadOfInventedContent() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(ErrorCode.BIZ_REJECTED, assertThrows(BizException.class, () -> service.transcribe(1L, request)).getErrorCode());
    }

    @Test void providerFailureAndTimeoutAreSanitizedAndReleaseTheUserSlot() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private-key-and-audio"));
        server.expect(requestTo(ENDPOINT)).andRespond(provider -> { throw new IOException("private-key-and-audio"); });
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            var error = assertThrows(BizException.class, () -> service.transcribe(1L, request));
            assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
            assertFalse(error.getMessage().contains("private-key-and-audio"));
        }
        assertEquals("空腹血糖5.2", service.transcribe(1L, request).text());
        server.verify();
    }

    @Test void providerRateLimitReturnsRetryableBusinessStatus() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, assertThrows(BizException.class, () -> service.transcribe(1L, request)).getErrorCode());
    }

    @Test void duplicateInFlightRequestDoesNotChargeTheProviderTwice() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.expect(requestTo(ENDPOINT)).andRespond(provider -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
            return withSuccess(RESULT, MediaType.APPLICATION_JSON).createResponse(provider);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.transcribe(1L, request));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(ErrorCode.TOO_MANY_REQUESTS, assertThrows(BizException.class, () -> service.transcribe(1L, request)).getErrorCode());
            } finally { release.countDown(); }
            assertEquals("空腹血糖5.2", first.get(5, TimeUnit.SECONDS).text());
        }
        server.verify();
    }

    @Test void authenticatedHttpContractReturnsDraftOnlyWithRequestId() throws Exception {
        var mock = mock(SpeechTranscriptionService.class);
        when(mock.transcribe(eq(7L), any())).thenReturn(new SpeechDtos.Result("确认后的文字"));
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechTranscriptionController(mock))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        RequestContext.setUserId(7L); RequestContext.setRequestId("speech-request");
        var response = mvc.perform(post("/api/v1/speech-transcriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"audioBase64\":\"SUQzBA==\",\"format\":\"mp3\",\"durationMs\":2000}"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(0, json.path("code").asInt());
        assertEquals("确认后的文字", json.path("data").path("text").asText());
        assertEquals("speech-request", json.path("requestId").asText());
        verify(mock).transcribe(eq(7L), any());
    }

    @Test void unauthorizedAndInvalidRequestsCannotCallTheTranscriber() throws Exception {
        var mock = mock(SpeechTranscriptionService.class);
        var controller = new SpeechTranscriptionController(mock);
        assertThrows(BizException.class, () -> controller.transcribe(request));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        RequestContext.setUserId(7L);
        assertEquals(400, mvc.perform(post("/api/v1/speech-transcriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"audioBase64\":\"\",\"format\":\"wav\",\"durationMs\":100}"))
                .andReturn().getResponse().getStatus());
        verifyNoInteractions(mock);
    }
}
