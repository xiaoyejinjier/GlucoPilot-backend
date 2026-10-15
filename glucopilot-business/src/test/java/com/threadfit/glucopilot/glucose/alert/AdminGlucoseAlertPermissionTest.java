package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.context.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真正的 Spring 方法鉴权代理，验证只读权限不能处理事件。 */
class AdminGlucoseAlertPermissionTest {
    private AnnotationConfigApplicationContext context;
    private AdminGlucoseAlertController controller;
    private GlucoseAlertService service;
    private Gate gate;

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean GlucoseAlertService service() { return mock(GlucoseAlertService.class); }
        @Bean AdminGlucoseAlertController controller(GlucoseAlertService service) { return new AdminGlucoseAlertController(service); }
        @Bean(name="ss") Gate gate() { return new Gate(); }
    }
    public static class Gate {
        Set<String> permissions=Set.of();
        public boolean hasPermi(String permission) { return permissions.contains(permission); }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class);
        controller=context.getBean(AdminGlucoseAlertController.class);
        service=context.getBean(GlucoseAlertService.class);gate=context.getBean(Gate.class);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator","unused","ROLE_TEST"));
    }
    @AfterEach void close() { context.close(); SecurityContextHolder.clearContext();RequestContext.clear(); }

    @Test void noPermissionsDenyListDetailAndHandlingBeforeServiceRuns() {
        assertThrows(AccessDeniedException.class,()->controller.page(null,null,null,null,null,new PageQuery()));
        assertThrows(AccessDeniedException.class,()->controller.detail(1));
        assertThrows(AccessDeniedException.class,()->controller.handle(1,input()));
        verifyNoInteractions(service);
    }
    @Test void readOnlyRoleCanViewButCannotHandle() {
        gate.permissions=Set.of("health:alert:view");
        controller.detail(1);verify(service).detail(1);
        assertThrows(AccessDeniedException.class,()->controller.handle(1,input()));
        verify(service,never()).handle(anyLong(),anyLong(),any());
    }
    @Test void handlingUsesAuthenticatedOpsIdentityAndStandardEnvelope() {
        gate.permissions=Set.of("health:alert:handle");RequestContext.setOpsId(7L);
        var input=input();
        var response=controller.handle(1,input);
        verify(service).handle(7,1,input);
        assertEquals(0,response.getCode());
    }
    private GlucoseAlertDtos.Handling input() {
        return new GlucoseAlertDtos.Handling(new BigDecimal("3.8"),LocalDateTime.of(2026,10,15,8,0),0L,"PROCESSING","核对中");
    }
}
