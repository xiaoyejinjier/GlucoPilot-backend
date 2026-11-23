package com.threadfit.glucopilot.doctor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.PermissionService;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.doctor.controller.DoctorPortalController;
import com.threadfit.glucopilot.doctor.service.DoctorPortalService;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

class DoctorMethodSecurityTest {
    @Configuration
    @EnableMethodSecurity
    static class Beans {
        @Bean
        DoctorPortalService doctors() {
            return mock(DoctorPortalService.class);
        }

        @Bean
        ConsultationService consultations() {
            return mock(ConsultationService.class);
        }

        @Bean
        DoctorPortalController controller(DoctorPortalService d, ConsultationService c) {
            return new DoctorPortalController(d, c);
        }

        @Bean
        com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService withdrawalService() {
            return mock(com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService.class);
        }

        @Bean
        com.threadfit.glucopilot.consultation.service.ConsultationMediaService media() {
            return mock(
                    com.threadfit.glucopilot.consultation.service.ConsultationMediaService.class);
        }

        @Bean
        com.threadfit.glucopilot.wallet.withdrawal.controller.AdminWithdrawalController
                adminWithdrawal(
                        com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService w) {
            return new com.threadfit.glucopilot.wallet.withdrawal.controller
                    .AdminWithdrawalController(
                    w,
                    mock(
                            com.threadfit.glucopilot.wallet.withdrawal.service
                                    .WithdrawalTransferService.class));
        }

        @Bean
        com.threadfit.glucopilot.doctor.controller.DoctorWithdrawalController doctorWithdrawal(
                DoctorPortalService d,
                com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService w) {
            return new com.threadfit.glucopilot.doctor.controller.DoctorWithdrawalController(
                    d,
                    w,
                    mock(
                            com.threadfit.glucopilot.wallet.withdrawal.service
                                    .WithdrawalTransferService.class));
        }

        @Bean
        com.threadfit.glucopilot.doctor.controller.DoctorCommunicationController communication(
                DoctorPortalService d,
                ConsultationService c,
                com.threadfit.glucopilot.consultation.service.ConsultationMediaService m) {
            return new com.threadfit.glucopilot.doctor.controller.DoctorCommunicationController(
                    d, c, m);
        }

        @Bean(name = "ss")
        PermissionService ss() {
            return new PermissionService();
        }
    }

    @AfterEach
    void cleanup() {
        RequestContext.clear();
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    void login(String role, Set<String> permissions) {
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        var user = new SysUser();
        user.setUserId(12L);
        var r = new SysRole();
        r.setRoleKey(role);
        r.setStatus("0");
        user.setRoles(List.of(r));
        var login = new LoginUser();
        login.setUser(user);
        login.setUserId(12L);
        login.setPermissions(permissions);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        RequestContext.setOpsId(7L);
    }

    @Test
    void revokingAFunctionBlocksItsActualControllerEvenWithADoctorRole() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            var controller = context.getBean(DoctorPortalController.class);
            login("doctor", Set.of("doctor:workbench:view"));
            assertDoesNotThrow(() -> controller.workbench(new PageQuery()));
            assertThrows(AccessDeniedException.class, () -> controller.income(new PageQuery()));
            assertThrows(AccessDeniedException.class, () -> controller.claim(1));
            login("operator", Set.of("doctor:workbench:view", "doctor:income:view"));
            assertThrows(AccessDeniedException.class, () -> controller.workbench(new PageQuery()));
        }
    }

    @Test
    void withdrawalApprovalIsAdminOnlyWhileDoctorsUseBoundIdentityForIncomeAndReplies() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            var d = context.getBean(DoctorPortalService.class);
            when(d.doctorId(7)).thenReturn(22L);
            var admin =
                    context.getBean(
                            com.threadfit.glucopilot.wallet.withdrawal.controller
                                    .AdminWithdrawalController.class);
            var own =
                    context.getBean(
                            com.threadfit.glucopilot.doctor.controller.DoctorWithdrawalController
                                    .class);
            var communication =
                    context.getBean(
                            com.threadfit.glucopilot.doctor.controller.DoctorCommunicationController
                                    .class);
            var w =
                    context.getBean(
                            com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService
                                    .class);
            var c = context.getBean(ConsultationService.class);
            login(
                    "doctor",
                    Set.of(
                            "doctor:income:view",
                            "doctor:consultation:manage",
                            "doctor:withdrawal:manage"));
            own.summary();
            verify(w).summary(22L);
            var reply =
                    new com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.Send(
                            "测试回复", "bound_doctor_reply");
            communication.send(8, reply);
            verify(c).sendMessage(22L, 8L, reply);
            assertThrows(AccessDeniedException.class, () -> admin.list(null, 33L, new PageQuery()));
            login("doctor", Set.of("doctor:workbench:view"));
            assertThrows(AccessDeniedException.class, own::summary);
            assertThrows(AccessDeniedException.class, () -> communication.send(8, reply));
            login("admin", Set.of("doctor:withdrawal:manage"));
            assertDoesNotThrow(() -> admin.list("PENDING", 33L, new PageQuery()));
            verify(w).list(eq(33L), eq("PENDING"), any(PageQuery.class));
            assertThrows(AccessDeniedException.class, own::summary);
        }
    }
}
