package com.threadfit.glucopilot.ai.business.meal.service.impl;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.MealAnalysisService;
import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;

@Service
@RequiredArgsConstructor
public class MealAnalysisServiceImpl implements MealAnalysisService {
    private final MediaFileService mediaFiles;
    private final VisionAnalyzer vision;
    private final MealFoodCatalogService catalog;
    private final AIProperties properties;
    private final JsonUtils json;
    private final IdempotencyStore idempotency;

    public MealAnalysisDtos.Result analyze(Long userId, MealAnalysisDtos.Request request) {
        if (!GlucoseNodes.isMealNode(request.getNodeCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择早餐、中餐或晚餐节点");
        }
        MediaFile media = mediaFiles.findOwnedMedia(request.getMediaId(), userId, MediaBizType.MEAL);
        if (media == null) throw new BizException(ErrorCode.NOT_FOUND, "餐食照片不存在或不属于当前用户");
        String foodText = request.getFoodText() == null ? "" : request.getFoodText().trim();
        String header = RequestContext.getIdempotencyKey();
        String key = header == null ? null : IdempotencyStore.key(
                "meal:analysis:" + request.getMediaId() + ":" + hash(foodText), userId, header);
        if (key != null) {
            var snapshot = idempotency.find(key);
            if (snapshot.isPresent()) return json.fromJson(snapshot.get(), MealAnalysisDtos.Result.class);
        }
        String model = properties.getMeal().getModel();
        String raw;
        try {
            raw = vision.analyze(media, SceneCode.MEAL_ANALYSIS,
                    "识别图片中的餐食并输出约定 JSON。补充文字仅是识别线索：" + foodText);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "餐食识别调用失败，请检查千问视觉模型及 API 配置后重试");
        }
        MealAnalysisDtos.ModelResult recognized = parse(raw);
        List<MealAnalysisDtos.Item> items = new ArrayList<>();
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (recognized.getWarning() != null && !recognized.getWarning().isBlank()) {
            warnings.add(recognized.getWarning().substring(0, Math.min(200, recognized.getWarning().length())));
        }
        int newCount = 0;
        for (MealAnalysisDtos.ModelFood food : recognized.getFoods()) {
            if (food == null) continue;
            String name = MealFoodCatalogService.normalizeName(food.getName());
            if (name.isBlank() || name.length() > 64 || !names.add(name)) continue;
            MealFoodCatalogService.Resolution resolution = catalog.resolve(food, model);
            items.add(resolution.item());
            if (resolution.added()) newCount++;
            if (resolution.item().getWeightG() == null) warnings.add("请确认食物重量后保存");
            if (resolution.item().getCarbsG() == null) warnings.add("部分食物或营养不确定，请补充说明后重新识别");
            if ("AI_ESTIMATE".equals(resolution.item().getNutritionSource())) warnings.add("新食品营养为模型估算，已加入食品库待运营核对");
        }
        if (items.isEmpty()) throw new BizException(ErrorCode.BIZ_REJECTED, "未识别出餐食，请重新拍摄清晰照片");
        var result = MealAnalysisDtos.Result.builder().model(model).items(items)
                .carbsG(total(items, false)).caloriesKcal(total(items, true))
                .matchedCount((int) items.stream().filter(item -> "LIBRARY".equals(item.getNutritionSource())).count())
                .newFoodsCount(newCount).warnings(List.copyOf(warnings)).build();
        if (key != null) idempotency.saveIfAbsent(key, result);
        return result;
    }

    MealAnalysisDtos.ModelResult parse(String raw) {
        if (raw == null || raw.length() > 50000) throw new BizException(ErrorCode.BIZ_REJECTED, "餐食识别结果无效，请重试");
        String text = raw.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        MealAnalysisDtos.ModelResult result;
        try {
            result = json.fromJson(text, MealAnalysisDtos.ModelResult.class);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "模型没有返回有效的食物列表，请重试");
        }
        if (result == null || !Integer.valueOf(1).equals(result.getSchemaVersion())
                || result.getFoods() == null || result.getFoods().size() > 12) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "餐食识别结果格式不正确，请重试");
        }
        return result;
    }

    private BigDecimal total(List<MealAnalysisDtos.Item> items, boolean calories) {
        BigDecimal total = BigDecimal.ZERO;
        for (var item : items) {
            BigDecimal value = calories ? item.getCaloriesKcal() : item.getCarbsG();
            if (value == null) return null;
            total = total.add(value);
        }
        return total;
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
