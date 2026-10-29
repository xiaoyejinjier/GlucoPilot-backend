package com.threadfit.glucopilot.common.exception;

import com.threadfit.glucopilot.common.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class GlobalExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Routes())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void unregisteredRouteUsesNotFoundInsteadOfSystemError() throws Exception {
        RequestContext.setRequestId("route-version-check");
        var response = mvc.perform(get("/api/v1/not-installed")).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(40400, json.get("code").asInt());
        assertEquals("接口不存在，请确认后端版本与请求地址", json.get("message").asText());
        assertEquals("route-version-check", json.get("requestId").asText());
    }

    @Test
    void missingStaticResourceAlsoUsesApiEnvelope() throws Exception {
        var response = mvc.perform(get("/test/resource-missing")).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        assertEquals(40400, new ObjectMapper().readTree(response.getContentAsByteArray()).get("code").asInt());
    }

    @Test
    void unsupportedMethodKeepsAllowHeaderAndDoesNotBecome500() throws Exception {
        var response = mvc.perform(post("/test/read-only")).andReturn().getResponse();
        assertEquals(405, response.getStatus());
        assertTrue(response.getHeader("Allow").contains("GET"));
        assertEquals(40500, new ObjectMapper().readTree(response.getContentAsByteArray()).get("code").asInt());
    }

    @Test
    void businessResourceNotFoundKeepsItsOriginalMessage() throws Exception {
        var response = mvc.perform(get("/test/business-missing")).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(40400, json.get("code").asInt());
        assertEquals("会话不存在", json.get("message").asText());
    }

    @Test
    void dataIntegrityViolationUsesBadRequestWithoutSqlDetails() throws Exception {
        var response = mvc.perform(get("/test/constraint-violation")).andReturn().getResponse();
        assertEquals(400, response.getStatus());
        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(40000, json.get("code").asInt());
        assertEquals("数据校验失败，请检查提交内容", json.get("message").asText());
        assertFalse(json.get("message").asText().contains("chk_gr_val"));
    }

    @RestController
    static class Routes {
        @GetMapping("/test/read-only")
        String readOnly() { return "ok"; }

        @GetMapping("/test/resource-missing")
        String missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "not-installed", "/test/resource-missing");
        }

        @GetMapping("/test/business-missing")
        String missingBusinessResource() {
            throw new BizException(com.threadfit.glucopilot.common.api.ErrorCode.NOT_FOUND, "会话不存在");
        }

        @GetMapping("/test/constraint-violation")
        String constraintViolation() {
            throw new DataIntegrityViolationException("Check constraint 'chk_gr_val' is violated");
        }
    }
}
