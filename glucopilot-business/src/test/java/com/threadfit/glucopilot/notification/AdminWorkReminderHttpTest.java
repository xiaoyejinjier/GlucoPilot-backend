package com.threadfit.glucopilot.notification;

import com.threadfit.glucopilot.auth.service.RuoYiAuthorizationService;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AdminWorkReminderHttpTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean AdminWorkReminderService service(){return mock(AdminWorkReminderService.class);}
        @Bean RuoYiAuthorizationService auth(){return mock(RuoYiAuthorizationService.class);}
        @Bean AdminWorkReminderController controller(AdminWorkReminderService s,RuoYiAuthorizationService a){return new AdminWorkReminderController(s,a);}
        @Bean(name="ss") Gate gate(){return new Gate();}
    }
    public static class Gate {
        public boolean hasAnyPermi(String codes){var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getAuthorities().stream().anyMatch(a->Arrays.asList(codes.split(",")).contains(a.getAuthority()));}
    }
    @Test void endpointRequiresOperatorAndPermissionAndUsesActualServerGrants()throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var controller=context.getBean(AdminWorkReminderController.class);var service=context.getBean(AdminWorkReminderService.class);var authorization=context.getBean(RuoYiAuthorizationService.class);
            SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,controller::summary);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("reader","unused","user:manage"));assertThrows(AccessDeniedException.class,controller::summary);verifyNoInteractions(service,authorization);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("feedback-manager","unused","feedback:manage"));RequestContext.setUserId(9L);assertThrows(BizException.class,controller::summary);verifyNoInteractions(service,authorization);
            RequestContext.setOpsId(7L);when(authorization.permissionCodesOf(7L)).thenReturn(Set.of("feedback:manage"));when(service.summary(Set.of("feedback:manage"))).thenReturn(new AdminWorkReminderService.Summary(1,List.of(new AdminWorkReminderService.Item("FEEDBACK","新反馈",1,null))));
            var response=MockMvcBuilders.standaloneSetup(controller).build().perform(get("/admin/v1/work-reminders?opsId=99")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("FEEDBACK"));assertFalse(response.getContentAsString().contains("REPORT"));verify(authorization).permissionCodesOf(7L);verify(service).summary(Set.of("feedback:manage"));
        }finally{SecurityContextHolder.clearContext();RequestContext.clear();}
    }
}
