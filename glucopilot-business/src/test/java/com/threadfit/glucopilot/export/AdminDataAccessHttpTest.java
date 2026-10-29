package com.threadfit.glucopilot.export;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.admin.AdminUserService;
import com.threadfit.glucopilot.user.controller.AdminUserController;
import com.threadfit.glucopilot.user.dto.AdminUserDtos;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
class AdminDataAccessHttpTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean DataExportService exports(){return mock(DataExportService.class);}
        @Bean AdminUserService users(){return mock(AdminUserService.class);}
        @Bean DataExportController exportController(DataExportService s){return new DataExportController(s);}
        @Bean AdminUserController userController(AdminUserService s){return new AdminUserController(s);}
        @Bean(name="ss") Gate gate(){return new Gate();}
    }
    public static class Gate {public boolean hasPermi(String code){var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getAuthorities().stream().anyMatch(a->code.equals(a.getAuthority()));}}
    @Test void userPhoneAndExportFilesRequireTheirOwnPermissions() throws Exception {
        try(var c=new AnnotationConfigApplicationContext(Config.class)){
            var users=c.getBean(AdminUserController.class);var exports=c.getBean(DataExportController.class);
            SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->users.detail(1L));assertThrows(AccessDeniedException.class,()->exports.fileAdmin(1));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("reader","unused","content:news:list"));assertThrows(AccessDeniedException.class,()->users.detail(1L));assertThrows(AccessDeniedException.class,()->exports.generate(1));
            verifyNoInteractions(c.getBean(AdminUserService.class),c.getBean(DataExportService.class));
            when(c.getBean(AdminUserService.class).detail(1L)).thenReturn(AdminUserDtos.UserItem.builder().id("1").phone("13812345678").build());
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("manager","unused","user:manage"));
            var response=MockMvcBuilders.standaloneSetup(users).build().perform(get("/admin/v1/users/1")).andReturn().getResponse();assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("13812345678"));assertFalse(response.getContentAsString().contains("openid"));assertThrows(AccessDeniedException.class,()->exports.fileAdmin(1));
            when(c.getBean(DataExportService.class).file(null,1)).thenReturn(new byte[]{37,80,68,70,45});
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("exporter","unused","export:task:manage"));
            var file=MockMvcBuilders.standaloneSetup(exports).build().perform(get("/admin/v1/exports/1/file")).andReturn().getResponse();assertEquals(DataExportService.MIME,file.getContentType());assertEquals("no-store",file.getHeader("Cache-Control"));assertTrue(file.getHeader("Content-Disposition").endsWith(".pdf"));
        }finally{SecurityContextHolder.clearContext();RequestContext.clear();}
    }
    @Test void cClientCannotChooseAnotherUsersExportIdentity(){var service=mock(DataExportService.class);var controller=new DataExportController(service);try{RequestContext.clear();assertThrows(BizException.class,()->controller.fileMine(5));assertThrows(BizException.class,()->controller.removeMine(5));RequestContext.setUserId(9L);when(service.file(9L,5)).thenReturn(new byte[]{37,80,68,70,45});controller.fileMine(5);controller.removeMine(5);verify(service).file(9L,5);verify(service).remove(9L,5);verify(service,never()).file(null,5);}finally{RequestContext.clear();}}
}
