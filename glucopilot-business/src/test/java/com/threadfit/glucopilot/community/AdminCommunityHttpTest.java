package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AdminCommunityHttpTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean CommunityPublicationService service() { return mock(CommunityPublicationService.class); }
        @Bean AdminCommunityController controller(CommunityPublicationService service) { return new AdminCommunityController(service); }
        @Bean(name="ss") Gate gate() { return new Gate(); }
    }
    public static class Gate {
        public boolean hasPermi(String p) { var a=SecurityContextHolder.getContext().getAuthentication();return a!=null && a.getAuthorities().stream().anyMatch(role->p.equals(role.getAuthority())); }
        public boolean hasRole(String role) { return hasPermi("ROLE_"+role); }
    }
    @Test void adminListRouteUsesFiltersAndStandardEnvelope() throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var service=context.getBean(CommunityPublicationService.class);
            when(service.adminArticles(eq("PUBLISHED"),eq("OFFICIAL"),eq("标题"),any())).thenReturn(PageResult.of(2,10,0,List.of()));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops","n/a","content:news:list"));
            var response=MockMvcBuilders.standaloneSetup(context.getBean(AdminCommunityController.class)).build().perform(get("/admin/v1/community/articles").param("status","PUBLISHED").param("source","OFFICIAL").param("keyword","标题").param("pageNum","2").param("pageSize","10")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("\"code\":0"));
            verify(service).adminArticles(eq("PUBLISHED"),eq("OFFICIAL"),eq("标题"),argThat(q->q.getPageNum()==2 && q.getPageSize()==10));
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void anonymousAndAccountsWithoutReadPermissionCannotReadArticlesOrReports() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(AdminCommunityController.class);SecurityContextHolder.clearContext();
            assertThrows(AccessDeniedException.class,()->controller.article(7));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user","n/a","user:manage"));
            assertThrows(AccessDeniedException.class,()->controller.articles(null,null,null,new PageQuery()));
            assertThrows(AccessDeniedException.class,()->controller.reports(null,new PageQuery()));
            verifyNoInteractions(context.getBean(CommunityPublicationService.class));
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void mutationsRequireBothAdminRoleAndWritePermissionAndUseCurrentOpsId() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(AdminCommunityController.class);var review=new PublicationDtos.Review(3,"APPROVE","通过");
            for(String[] permissions:List.of(new String[]{"content:news:edit"},new String[]{"ROLE_admin"})) {
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops","n/a",permissions));
                assertThrows(AccessDeniedException.class,()->controller.review(7,review));
                assertThrows(AccessDeniedException.class,()->controller.retry(7));
                assertThrows(AccessDeniedException.class,()->controller.resolve(9,new PublicationDtos.Resolution("DISMISS","说明")));
            }
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops","n/a","ROLE_admin","content:news:edit"));
            RequestContext.setOpsId(99L);controller.review(7,review);controller.retry(7);controller.resolve(9,new PublicationDtos.Resolution("DISMISS","说明"));
            var service=context.getBean(CommunityPublicationService.class);verify(service).review(99,7,review);verify(service).retry(99,7,true);verify(service).resolveReport(eq(99L),eq(9L),any());
        } finally { RequestContext.clear();SecurityContextHolder.clearContext(); }
    }
    @Test void submissionHttpUsesAuthenticatedUserAndAcceptsCustomTagText() throws Exception {
        var service=mock(CommunityPublicationService.class);
        try {
            RequestContext.setUserId(2L);
            var mvc=MockMvcBuilders.standaloneSetup(new CommunityPublicationController(service)).build();
            var response=mvc.perform(post("/api/v1/community/articles").contentType("application/json")
                    .content("{\"title\":\"分享早餐\",\"body\":\"今天认真记录了自己的早餐，希望能和大家分享一下坚持记录的经验。\",\"tagCodes\":[],\"customTags\":[\"我的早餐\"],\"clientRequestId\":\"custom-http-001\"}")).andReturn().getResponse();
            assertEquals(200,response.getStatus());verify(service).submit(eq(2L),argThat(request->request.customTags().equals(List.of("我的早餐")) && request.tagCodes().isEmpty()));
        } finally { RequestContext.clear(); }
    }
}
