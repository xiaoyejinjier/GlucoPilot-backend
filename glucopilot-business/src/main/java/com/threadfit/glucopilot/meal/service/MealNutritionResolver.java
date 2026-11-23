package com.threadfit.glucopilot.meal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 餐食明细归一化（阶段 2b-1）。
 *
 * <p><b>只读</b>：只查 {@code food_nutrition}，不建草稿、不补图片、不写库、不读血糖。
 * 取行规则不在这里重写，统一走 {@link MealFoodCatalogService#pickBest}，
 * 否则识别链路与保存链路会出现"同一食物两种结论"的漂移。
 *
 * <h2>两条调用路径的策略差异（由 clientValuesFirst 决定，不写死）</h2>
 * <ul>
 *   <li><b>preview（clientValuesFirst=true）</b>：客户端送来的非空值一律保留，只补 null。
 *       预览没有服务端识别结果，客户端值就是唯一事实来源，也不存在伪造动机。</li>
 *   <li><b>save（clientValuesFirst=false）</b>：库内命中结果覆盖客户端值，防止伪造。
 *       {@code confidence} 例外——服务端在这里拿不到可信置信度，
 *       它必须由「分析快照」提供（引用字段在 2b-2 确定，可能是 mediaId 或 analysisToken）。
 *       因此本方法在 save 模式下<b>不填也不改 confidence</b>，留给调用方注入。</li>
 * </ul>
 *
 * <h2>已知边界</h2>
 * 现网食物库仅 21 条且全部已上架（2026-11-19 实测），因此 PENDING_REVIEW 分支只能在单测里覆盖。
 * 旧客户端不带分析引用时，调用方应 fallback 到"仅按 libraryStatus != AVAILABLE 判定低置信"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MealNutritionResolver {

    /**
     * 单次 IN 的上限。防止将来食物库膨胀后，一次解析把整表拉进内存或触发全表扫描。
     * 超出上限的明细按"未命中"处理（保持零写入、不报错）。
     */
    static final int MAX_IN_SIZE = 500;

    private final FoodNutritionMapper foods;

    /**
     * 归一化明细。返回新列表，元素为入参对象的浅拷贝结果（入参本身不被修改，避免调用方拿到被改写的请求体）。
     */
    public List<ItemInput> resolve(List<ItemInput> items, boolean clientValuesFirst) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ItemInput> targets = items.stream().filter(Objects::nonNull)
                .map(MealNutritionResolver::copyOf).toList();

        // 第 1 次查询：按 libraryFoodId 批量取
        Set<Long> ids = targets.stream().map(ItemInput::getLibraryFoodId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> idList = ids.stream().limit(MAX_IN_SIZE).toList();
        Map<Long, FoodNutrition> byId = idList.isEmpty() ? Map.of()
                : foods.selectList(new QueryWrapper<FoodNutrition>().in("id", idList))
                .stream().collect(Collectors.toMap(FoodNutrition::getId, Function.identity(), (a, b) -> a));

        // 第 2 次查询：按归一化名称批量取（ID 命中不到的项才需要）
        List<String> names = targets.stream()
                .filter(item -> item.getLibraryFoodId() == null || !byId.containsKey(item.getLibraryFoodId()))
                .map(item -> MealFoodCatalogService.normalizeName(item.getFoodName()))
                .filter(name -> !name.isEmpty())
                .distinct().limit(MAX_IN_SIZE).toList();
        Map<String, List<FoodNutrition>> byName = names.isEmpty() ? Map.of()
                : foods.selectList(new QueryWrapper<FoodNutrition>().in("food_name", names))
                .stream().collect(Collectors.groupingBy(FoodNutrition::getFoodName, LinkedHashMap::new, Collectors.toList()));

        List<ItemInput> resolved = new ArrayList<>(targets.size());
        for (ItemInput item : targets) {
            resolved.add(apply(item, find(item, byId, byName), clientValuesFirst));
        }
        return resolved;
    }

    private FoodNutrition find(ItemInput item, Map<Long, FoodNutrition> byId, Map<String, List<FoodNutrition>> byName) {
        if (item.getLibraryFoodId() != null) {
            FoodNutrition hit = byId.get(item.getLibraryFoodId());
            if (hit != null) {
                return hit;
            }
        }
        String name = MealFoodCatalogService.normalizeName(item.getFoodName());
        return MealFoodCatalogService.pickBest(byName.get(name), normalizeCategory(item.getCategoryCode()));
    }

    /** 与识别链路一致：分类为空或越界时按 9（零食及其他）处理，保证两边取行结果相同。 */
    private static int normalizeCategory(Integer categoryCode) {
        return categoryCode == null || categoryCode < 1 || categoryCode > 9 ? 9 : categoryCode;
    }

    private ItemInput apply(ItemInput item, FoodNutrition row, boolean clientValuesFirst) {
        boolean matched = row != null;
        boolean available = matched && Integer.valueOf(1).equals(row.getShelfStatus());
        item.setLibraryStatus(!matched ? "UNRESOLVED" : available ? "AVAILABLE" : "PENDING_REVIEW");

        if (clientValuesFirst) {
            if (!matched) {
                // 有意行为（非遗漏）：preview/save 遇到库内不存在的 libraryFoodId 一律清空为 null，
                // 并配 libraryStatus=UNRESOLVED；保留悬空引用会让「未命中营养库」与「有库条目」自相矛盾。
                item.setLibraryFoodId(null);
            } else if (item.getLibraryFoodId() == null) {
                item.setLibraryFoodId(row.getId());
            }
            if (item.getCategoryCode() == null && matched && row.getCategoryCode() != null) {
                item.setCategoryCode(row.getCategoryCode());
            }
            if (item.getGiValue() == null && matched) {
                item.setGiValue(row.getGiValue());
            }
            if (item.getNutritionSource() == null) {
                item.setNutritionSource(matched ? sourceOf(row) : "UNKNOWN");
            }
            // confidence 原样保留
            return item;
        }

        if (matched) {
            item.setLibraryFoodId(row.getId());
            if (row.getCategoryCode() != null) {
                item.setCategoryCode(row.getCategoryCode());
            }
            item.setGiValue(row.getGiValue());
            item.setNutritionSource(sourceOf(row));
        } else {
            item.setLibraryFoodId(null);
            item.setGiValue(null);
            item.setNutritionSource("UNKNOWN");
        }
        // confidence：save 路径由调用方以分析快照注入（2b-2 定字段），此处不动
        return item;
    }

    private static String sourceOf(FoodNutrition row) {
        return "AI_ESTIMATE".equals(row.getNutritionSource()) ? "AI_ESTIMATE" : "LIBRARY";
    }

    /** 浅拷贝：resolver 会改写明细字段，不应污染调用方的请求对象（尤其是幂等重试会复用同一请求体）。 */
    private static ItemInput copyOf(ItemInput source) {
        ItemInput target = new ItemInput();
        target.setFoodId(source.getFoodId());
        target.setLibraryFoodId(source.getLibraryFoodId());
        target.setFoodName(source.getFoodName());
        target.setCategoryCode(source.getCategoryCode());
        target.setWeightG(source.getWeightG());
        target.setCarbsG(source.getCarbsG());
        target.setCaloriesKcal(source.getCaloriesKcal());
        target.setGiValue(source.getGiValue());
        target.setNutritionSource(source.getNutritionSource());
        target.setConfidence(source.getConfidence());
        target.setUserCorrected(source.getUserCorrected());
        target.setLibraryStatus(source.getLibraryStatus());
        return target;
    }
}
