package com.threadfit.glucopilot.community.message.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.community.message.controller.AdminMessageController;
import com.threadfit.glucopilot.community.message.dto.AdminMessageDtos.MessageItem;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

class AdminMessageHttpTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminMessageService service() {
            return mock(AdminMessageService.class);
        }

        @Bean
        AdminMessageController controller(AdminMessageService service) {
            return new AdminMessageController(service);
        }

        @Bean(name = "ss")
        com.threadfit.glucopilot.community.article.service.AdminCommunityHttpTest.Gate gate() {
            return new com.threadfit.glucopilot.community.article.service.AdminCommunityHttpTest
                    .Gate();
        }
    }

    @Test
    void bothEndpointsRequireAdminAndUserManagementPermission() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminMessageController.class);
            for (var authorities :
                    List.of(
                            new String[] {},
                            new String[] {"user:manage"},
                            new String[] {"ROLE_admin"},
                            new String[] {"ROLE_editor", "user:manage"})) {
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new TestingAuthenticationToken("ops", "n/a", authorities));
                assertThrows(
                        AccessDeniedException.class,
                        () -> controller.messages(null, null, null, null, new PageQuery()));
                assertThrows(
                        AccessDeniedException.class,
                        () -> controller.conversation(1, 2, new PageQuery()));
            }
            verifyNoInteractions(context.getBean(AdminMessageService.class));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken(
                                    "ops", "n/a", "ROLE_admin", "user:manage"));
            controller.conversation(1, 2, new PageQuery());
            verify(context.getBean(AdminMessageService.class)).conversation(eq(1L), eq(2L), any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void bindsFiltersAndPaginationAndReturnsOnlyDocumentedFields() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var service = context.getBean(AdminMessageService.class);
            var item =
                    new MessageItem(
                            "9",
                            "1",
                            "甲",
                            "2",
                            "乙",
                            "你好",
                            LocalDateTime.of(2026, 10, 28, 10, 0),
                            null);
            when(service.messages(eq("你好"), eq(1L), eq(2L), eq("UNREAD"), any()))
                    .thenReturn(PageResult.of(2, 10, 11, List.of(item)));
            when(service.conversation(eq(1L), eq(2L), any()))
                    .thenReturn(PageResult.of(1, 20, 1, List.of(item)));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken(
                                    "ops", "n/a", "ROLE_admin", "user:manage"));
            RequestContext.setRequestId("admin-message-http-test");
            var mvc =
                    MockMvcBuilders.standaloneSetup(context.getBean(AdminMessageController.class))
                            .setControllerAdvice(new GlobalExceptionHandler())
                            .build();
            var response =
                    mvc.perform(
                                    get("/admin/v1/community/messages")
                                            .param("keyword", "你好")
                                            .param("senderId", "1")
                                            .param("recipientId", "2")
                                            .param("readState", "UNREAD")
                                            .param("pageNum", "2")
                                            .param("pageSize", "10"))
                            .andReturn()
                            .getResponse();
            assertEquals(200, response.getStatus());
            var body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("\"code\":0"));
            assertTrue(body.contains("admin-message-http-test"));
            assertTrue(body.contains("\"senderId\":\"1\""));
            assertTrue(body.contains("你好"));
            assertFalse(body.contains("clientRequestId"));
            assertFalse(body.contains("phone"));
            verify(service)
                    .messages(
                            eq("你好"),
                            eq(1L),
                            eq(2L),
                            eq("UNREAD"),
                            argThat(q -> q.getPageNum() == 2 && q.getPageSize() == 10));
            assertEquals(
                    200,
                    mvc.perform(get("/admin/v1/community/messages/conversation/1/2"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    400,
                    mvc.perform(get("/admin/v1/community/messages").param("pageSize", "101"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    400,
                    mvc.perform(get("/admin/v1/community/messages").param("senderId", "abc"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        } finally {
            SecurityContextHolder.clearContext();
            RequestContext.clear();
        }
    }
}
