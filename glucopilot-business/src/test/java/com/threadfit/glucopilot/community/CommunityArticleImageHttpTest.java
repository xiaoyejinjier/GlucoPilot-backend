package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.context.RequestContext;
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

class CommunityArticleImageHttpTest {
    @Test void replyRouteBindsPathTargetInsteadOfTrustingBody() throws Exception {
        var service=mock(CommunityService.class);var controller=new CommunityController(service);
        RequestContext.setUserId(2L);
        try {
            var result=MockMvcBuilders.standaloneSetup(controller).build().perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/news/20/comments/7/replies")
                    .contentType("application/json").content("{\"content\":\"reply text\",\"clientRequestId\":\"reply-wire-01\",\"replyToCommentId\":99}"))
                    .andReturn().getResponse();
            assertEquals(200,result.getStatus());
            verify(service).addComment(2,20,new CommunityDtos.CommentRequest("reply text","reply-wire-01",7L));
        } finally {RequestContext.clear();}
    }
    @Test void videoRouteRequiresPermissionAndKeepsPrivateBinary() throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var service=context.getBean(CommunityArticleImageService.class);
            var controller=context.getBean(CommunityArticleImageController.class);
            SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->controller.adminVideo(20,60));
            when(service.read(null,20L,60,true)).thenReturn(new CommunityArticleImageService.Image(new byte[]{1,2,3},"video/mp4"));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops","unused","content:news:list"));
            var response=MockMvcBuilders.standaloneSetup(controller).build().perform(get("/admin/v1/community/articles/20/videos/60")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("video/mp4",response.getContentType());assertEquals("no-store",response.getHeader("Cache-Control"));
            assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());
        }finally{SecurityContextHolder.clearContext();RequestContext.clear();}
    }
    @Configuration @EnableMethodSecurity static class Config {
        @Bean CommunityArticleImageService images() { return mock(CommunityArticleImageService.class); }
        @Bean CommunityArticleImageController controller(CommunityArticleImageService images) { return new CommunityArticleImageController(images); }
        @Bean(name="ss") FoodGate gate() { return new FoodGate(); }
    }
    public static class FoodGate {
        public boolean hasPermi(String permission) {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            return auth!=null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a->permission.equals(a.getAuthority()));
        }
    }
    @Test void adminPhotoRequiresNewsPermissionAndReturnsPrivateBinary() throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var service=context.getBean(CommunityArticleImageService.class);
            var controller=context.getBean(CommunityArticleImageController.class);
            SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->controller.admin(20,50));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user","unused","user:manage"));
            assertThrows(AccessDeniedException.class,()->controller.admin(20,50));verifyNoInteractions(service);
            when(service.read(null,20L,50)).thenReturn(new CommunityArticleImageService.Image(new byte[]{1,2,3},"image/png"));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops","unused","content:news:list"));
            var response=MockMvcBuilders.standaloneSetup(controller).build().perform(get("/admin/v1/community/articles/20/images/50")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("image/png",response.getContentType());
            assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
            assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());
        } finally { SecurityContextHolder.clearContext();RequestContext.clear(); }
    }
}
