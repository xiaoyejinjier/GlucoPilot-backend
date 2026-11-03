package com.threadfit.glucopilot.community.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.community.recommendation.controller.AdminRecommendationController;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

class AdminRecommendationHttpTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminRecommendationService service() {
            return mock(AdminRecommendationService.class);
        }

        @Bean
        AdminRecommendationController controller(AdminRecommendationService service) {
            return new AdminRecommendationController(service);
        }

        @Bean(name = "ss")
        com.threadfit.glucopilot.community.article.service.AdminCommunityHttpTest.Gate gate() {
            return new com.threadfit.glucopilot.community.article.service.AdminCommunityHttpTest
                    .Gate();
        }
    }

    @Test
    void recommendationsRequireBothAdministratorRoleAndUserManagementPermission() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminRecommendationController.class);
            for (var permissions :
                    List.of(
                            new String[] {},
                            new String[] {"user:manage"},
                            new String[] {"ROLE_admin"},
                            new String[] {"ROLE_editor", "user:manage"})) {
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new TestingAuthenticationToken("ops", "n/a", permissions));
                assertThrows(AccessDeniedException.class, () -> controller.report(30));
            }
            verifyNoInteractions(context.getBean(AdminRecommendationService.class));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken(
                                    "ops", "n/a", "ROLE_admin", "user:manage"));
            controller.report(30);
            verify(context.getBean(AdminRecommendationService.class)).report(30);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
