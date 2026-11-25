package com.threadfit.glucopilot.food.gi;

import com.threadfit.glucopilot.food.service.FoodCatalogCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 联网查找在事务外；写值、来源留痕和缓存版本在一个短事务内完成。 */
@Service
@RequiredArgsConstructor
public class FoodGiWriter {
    private final FoodGiMapper mapper;
    private final FoodCatalogCache cache;

    @Transactional
    public void complete(FoodGiMapper.Candidate item, String lease, FoodGiResult result) {
        if (mapper.lockClaim(item.getFoodId(), lease) == null) return;
        if (result.value() != null) {
            if (result.value().signum() <= 0 || result.value().compareTo(new java.math.BigDecimal("200")) > 0)
                throw new IllegalArgumentException("Invalid reference GI");
            if (mapper.fillGi(item, result.value()) == 1) cache.advanceCatalogVersion();
            else result = new FoodGiResult("SKIPPED", null, result.referenceFood(), result.sourceUrl(),
                    "食品已由人工维护、改名或删除，未覆盖原数据");
        }
        if (mapper.complete(item, lease, result) != 1) throw new IllegalStateException("GI claim was lost");
    }
}
