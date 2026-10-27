package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.meal.controller.MealPhotoController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class MealPhotoHttpTest {
    @Test void currentUserIsRequiredBeforeCallingStorageService() {
        var service=mock(MealPhotoService.class);var controller=new MealPhotoController(service);
        RequestContext.clear();assertThrows(BizException.class,()->controller.image(88));verifyNoInteractions(service);
    }
    @Test void authenticatedOwnerGetsBinaryWithPrivateCacheHeaders() throws Exception {
        var service=mock(MealPhotoService.class);when(service.read(1,88)).thenReturn(new MealPhotoService.Image(new byte[]{1,2,3},"image/jpeg"));
        RequestContext.setUserId(1L);
        try {
            var response=MockMvcBuilders.standaloneSetup(new MealPhotoController(service)).build()
                    .perform(get("/api/v1/meal-records/images/88")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("image/jpeg",response.getContentType());
            assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
            assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());verify(service).read(1,88);
        } finally {RequestContext.clear();}
    }
}
