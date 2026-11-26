package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.gi.FoodGiMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoodGiDemoServiceTest {
    @Test void displayOnlyFieldDoesNotReplaceRealGiOrChangeBusinessFoodData() {
        var mapper = mock(FoodGiMapper.class); var service = new FoodGiDemoService(mapper);
        var demo = new FoodGiMapper.Demo(); demo.setFoodId(2L); demo.setGiDemoValue(new BigDecimal("15"));
        when(mapper.demoValues(List.of(2L))).thenReturn(List.of(demo));
        var real = FoodDtos.FoodItem.builder().id("1").giValue(new BigDecimal("36")).build();
        var unknown = FoodDtos.FoodItem.builder().id("2").build();
        service.publicItems(List.of(real, unknown));
        assertEquals(new BigDecimal("36"), real.getGiValue());
        assertNull(real.getGiDemoValue());
        assertNull(unknown.getGiValue());
        assertEquals(new BigDecimal("15"), unknown.getGiDemoValue());
        verify(mapper, times(1)).demoValues(List.of(2L));
    }

    @Test void adminReadsDemoWithoutPopulatingGiEditFieldAndEmptyPageSkipsQuery() {
        var mapper = mock(FoodGiMapper.class); var service = new FoodGiDemoService(mapper);
        service.publicItems(List.of()); service.adminItems(List.of());
        verifyNoInteractions(mapper);
        var demo = new FoodGiMapper.Demo(); demo.setFoodId(2L); demo.setGiDemoValue(new BigDecimal("15"));
        when(mapper.demoValues(List.of(2L))).thenReturn(List.of(demo));
        var food = AdminFoodDtos.FoodItem.builder().id(2L).build();
        service.adminItems(List.of(food));
        assertNull(food.getGi());
        assertEquals(new BigDecimal("15"), food.getGiDemoValue());
    }
}
