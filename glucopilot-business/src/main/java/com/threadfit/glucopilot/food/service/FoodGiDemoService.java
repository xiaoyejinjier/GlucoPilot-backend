package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.gi.FoodGiMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 粗略模拟值只附加到HTTP展示DTO，业务服务、AI工具和餐食计算继续读取真实GI。 */
@Service
@RequiredArgsConstructor
public class FoodGiDemoService {
    private final FoodGiMapper mapper;

    public void publicItems(List<FoodDtos.FoodItem> items) {
        var ids = items.stream().filter(item -> item.getGiValue() == null)
                .map(item -> Long.valueOf(item.getId())).toList();
        var values = values(ids);
        for (var item : items) item.setGiDemoValue(item.getGiValue() == null ? values.get(Long.valueOf(item.getId())) : null);
    }

    public void adminItems(List<AdminFoodDtos.FoodItem> items) {
        var ids = items.stream().filter(item -> item.getGi() == null).map(AdminFoodDtos.FoodItem::getId).toList();
        var values = values(ids);
        for (var item : items) item.setGiDemoValue(item.getGi() == null ? values.get(item.getId()) : null);
    }

    private Map<Long, BigDecimal> values(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return mapper.demoValues(ids).stream().collect(Collectors.toMap(
                FoodGiMapper.Demo::getFoodId, FoodGiMapper.Demo::getGiDemoValue));
    }
}
