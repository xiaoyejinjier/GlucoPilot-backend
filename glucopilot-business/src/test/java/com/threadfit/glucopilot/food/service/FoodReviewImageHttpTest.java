package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.food.controller.FoodReviewImageController;
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

class FoodReviewImageHttpTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean FoodReviewImageService service() { return mock(FoodReviewImageService.class); }
        @Bean FoodReviewImageController controller(FoodReviewImageService service) { return new FoodReviewImageController(service); }
        @Bean(name="ss") Gate gate() { return new Gate(); }
    }
    public static class Gate {
        public boolean hasPermi(String permission) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null && auth.isAuthenticated()
                    && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
        }
    }

    @Test void authorizedAdminReceivesBinaryWithoutCache() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var service = context.getBean(FoodReviewImageService.class);
            when(service.read(7L)).thenReturn(new FoodReviewImageService.Image(new byte[]{1,2,3}, "image/jpeg"));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops", "n/a", "food:library:manage"));
            var response = MockMvcBuilders.standaloneSetup(context.getBean(FoodReviewImageController.class)).build()
                    .perform(get("/admin/v1/foods/7/review-image")).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertEquals("image/jpeg", response.getContentType());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertArrayEquals(new byte[]{1,2,3}, response.getContentAsByteArray());
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void anonymousAndUnprivilegedAccountsCannotReadPhotos() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            SecurityContextHolder.clearContext();
            var controller = context.getBean(FoodReviewImageController.class);
            assertThrows(AccessDeniedException.class, () -> controller.image(7L));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("reader", "n/a", "user:manage"));
            assertThrows(AccessDeniedException.class, () -> controller.image(7L));
            verifyNoInteractions(context.getBean(FoodReviewImageService.class));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
