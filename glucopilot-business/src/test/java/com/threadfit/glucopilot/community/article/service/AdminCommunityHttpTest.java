package com.threadfit.glucopilot.community.article.service;

import static com.threadfit.glucopilot.community.comment.dto.AdminCommentDtos.*;
import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.article.controller.AdminCommunityController;
import com.threadfit.glucopilot.community.article.controller.CommunityPublicationController;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.comment.controller.AdminCommunityCommentController;
import com.threadfit.glucopilot.community.comment.dto.AdminCommentDtos;
import com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;
import com.threadfit.glucopilot.community.report.controller.AdminCommunityReportController;
import com.threadfit.glucopilot.community.report.dto.ReportDtos;
import com.threadfit.glucopilot.community.report.service.CommunityReportService;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

public class AdminCommunityHttpTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        CommunityPublicationService service() {
            return mock(CommunityPublicationService.class);
        }

        @Bean
        CommunityModerationQueue queue() {
            return mock(CommunityModerationQueue.class);
        }

        @Bean
        CommunityReportService reports() {
            return mock(CommunityReportService.class);
        }

        @Bean
        AdminCommunityCommentService comments() {
            return mock(AdminCommunityCommentService.class);
        }

        @Bean
        AdminCommunityReportController reportController(CommunityReportService reports) {
            return new AdminCommunityReportController(reports);
        }

        @Bean
        AdminCommunityCommentController commentController(
                AdminCommunityCommentService comments, CommunityModerationQueue queue) {
            return new AdminCommunityCommentController(comments, queue);
        }

        @Bean
        AdminCommunityController controller(
                CommunityPublicationService service, CommunityModerationQueue queue) {
            return new AdminCommunityController(service, queue);
        }

        @Bean(name = "ss")
        Gate gate() {
            return new Gate();
        }
    }

    public static class Gate {
        public boolean hasPermi(String p) {
            var a = SecurityContextHolder.getContext().getAuthentication();
            return a != null
                    && a.getAuthorities().stream().anyMatch(role -> p.equals(role.getAuthority()));
        }

        public boolean hasRole(String role) {
            return hasPermi("ROLE_" + role);
        }
    }

    @Test
    void adminListRouteUsesFiltersAndStandardEnvelope() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var service = context.getBean(CommunityPublicationService.class);
            when(service.adminArticles(eq("PUBLISHED"), eq("OFFICIAL"), eq("标题"), any()))
                    .thenReturn(PageResult.of(2, 10, 0, List.of()));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken("ops", "n/a", "content:news:list"));
            var response =
                    MockMvcBuilders.standaloneSetup(context.getBean(AdminCommunityController.class))
                            .build()
                            .perform(
                                    get("/admin/v1/community/articles")
                                            .param("status", "PUBLISHED")
                                            .param("source", "OFFICIAL")
                                            .param("keyword", "标题")
                                            .param("pageNum", "2")
                                            .param("pageSize", "10"))
                            .andReturn()
                            .getResponse();
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("\"code\":0"));
            verify(service)
                    .adminArticles(
                            eq("PUBLISHED"),
                            eq("OFFICIAL"),
                            eq("标题"),
                            argThat(q -> q.getPageNum() == 2 && q.getPageSize() == 10));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anonymousAndAccountsWithoutReadPermissionCannotReadArticlesOrReports() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminCommunityController.class);
            SecurityContextHolder.clearContext();
            assertThrows(AccessDeniedException.class, () -> controller.article(7));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken("user", "n/a", "user:manage"));
            assertThrows(
                    AccessDeniedException.class,
                    () -> controller.articles(null, null, null, new PageQuery()));
            assertThrows(
                    AccessDeniedException.class,
                    () ->
                            context.getBean(AdminCommunityReportController.class)
                                    .reports(null, new PageQuery()));
            verifyNoInteractions(context.getBean(CommunityPublicationService.class));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mutationsRequireWritePermissionAndUseCurrentOpsId() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminCommunityController.class);
            var review = new PublicationDtos.Review(3, "APPROVE", "通过");
            // 只有 ROLE_admin、没有 content:news:edit：仍然拒绝——角色不能代替权限点
            SecurityContextHolder.getContext()
                    .setAuthentication(new TestingAuthenticationToken("ops", "n/a", "ROLE_admin"));
            assertThrows(AccessDeniedException.class, () -> controller.review(7, review));
            assertThrows(AccessDeniedException.class, () -> controller.retry(7));
            assertThrows(
                    AccessDeniedException.class,
                    () ->
                            context.getBean(AdminCommunityReportController.class)
                                    .resolve(9, new ReportDtos.Resolution("DISMISS", "说明")));
            // 有 content:news:edit、没有 admin 角色：放行——谁能拿到这个权限点，谁就能治理社区
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken("ops", "n/a", "content:news:edit"));
            RequestContext.setOpsId(99L);
            controller.review(7, review);
            controller.retry(7);
            context.getBean(AdminCommunityReportController.class)
                    .resolve(9, new ReportDtos.Resolution("DISMISS", "说明"));
            var service = context.getBean(CommunityPublicationService.class);
            verify(service).review(99, 7, review);
            verify(service).retry(99, 7, true);
            verify(context.getBean(CommunityReportService.class))
                    .resolveReport(eq(99L), eq(9L), any());
        } finally {
            RequestContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void visibilityRouteRequiresAdminWritePermissionAndPassesFlagThrough() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminCommunityController.class);
            var queue = context.getBean(CommunityModerationQueue.class);
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken("ops", "n/a", "content:news:list"));
            assertThrows(
                    AccessDeniedException.class,
                    () ->
                            context.getBean(AdminCommunityCommentController.class)
                                    .setCommentVisibility(
                                            5, new AdminCommentDtos.CommentVisibility(true)));
            verifyNoInteractions(queue);
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken(
                                    "ops", "n/a", "ROLE_admin", "content:news:edit"));
            RequestContext.setOpsId(99L);
            context.getBean(AdminCommunityCommentController.class)
                    .setCommentVisibility(5, new AdminCommentDtos.CommentVisibility(true));
            context.getBean(AdminCommunityCommentController.class)
                    .setCommentVisibility(5, new AdminCommentDtos.CommentVisibility(false));
            verify(queue).setCommentHidden(99L, 5L, true);
            verify(queue).setCommentHidden(99L, 5L, false);
        } finally {
            RequestContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void submissionHttpUsesAuthenticatedUserAndAcceptsCustomTagText() throws Exception {
        var service = mock(CommunityPublicationService.class);
        try {
            RequestContext.setUserId(2L);
            var mvc =
                    MockMvcBuilders.standaloneSetup(new CommunityPublicationController(service))
                            .build();
            var response =
                    mvc.perform(
                                    post("/api/v1/community/articles")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"title\":\"分享早餐\",\"body\":\"今天认真记录了自己的早餐，希望能和大家分享一下坚持记录的经验。\",\"tagCodes\":[],\"customTags\":[\"我的早餐\"],\"clientRequestId\":\"custom-http-001\"}"))
                            .andReturn()
                            .getResponse();
            assertEquals(200, response.getStatus());
            verify(service)
                    .submit(
                            eq(2L),
                            argThat(
                                    request ->
                                            request.customTags().equals(List.of("我的早餐"))
                                                    && request.tagCodes().isEmpty()));
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void communityFeedDefaultsToRecommendationsAndUsesAuthenticatedViewer() throws Exception {
        var service = mock(CommunityPublicationService.class);
        when(service.feed(eq(36L), isNull(), eq(false), isNull(), eq("RECOMMEND"), any()))
                .thenReturn(PageResult.empty(1, 20));
        when(service.feed(eq(36L), eq("diet"), eq(false), isNull(), eq("LATEST"), any()))
                .thenReturn(PageResult.empty(1, 10));
        var mvc =
                MockMvcBuilders.standaloneSetup(new CommunityPublicationController(service))
                        .setControllerAdvice(
                                new com.threadfit.glucopilot.common.exception
                                        .GlobalExceptionHandler())
                        .build();
        try {
            RequestContext.setUserId(36L);
            RequestContext.setRequestId("recommendation-http-test");
            var response = mvc.perform(get("/api/v1/community/articles")).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("recommendation-http-test"));
            verify(service).feed(eq(36L), isNull(), eq(false), isNull(), eq("RECOMMEND"), any());
            assertEquals(
                    200,
                    mvc.perform(
                                    get("/api/v1/community/articles")
                                            .param("sort", "LATEST")
                                            .param("tag", "diet")
                                            .param("pageSize", "10"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            verify(service)
                    .feed(
                            eq(36L),
                            eq("diet"),
                            eq(false),
                            isNull(),
                            eq("LATEST"),
                            argThat(q -> q.getPageSize() == 10));
            RequestContext.clear();
            assertEquals(
                    401,
                    mvc.perform(get("/api/v1/community/articles"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        } finally {
            RequestContext.clear();
        }
    }
}
