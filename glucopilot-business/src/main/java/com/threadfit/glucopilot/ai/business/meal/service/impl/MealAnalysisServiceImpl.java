package com.threadfit.glucopilot.ai.business.meal.service.impl;

import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.MealAnalysisService;
import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;

@Slf4j
@Service
@RequiredArgsConstructor
public class MealAnalysisServiceImpl implements MealAnalysisService {
    private final MediaFileService mediaFiles;
    private final VisionAnalyzer vision;
    private final MealFoodCatalogService catalog;
    /** 回显 / 溯源用的模型编码：必须走场景解析，否则记录里写的是 yaml 的模型、实际调的是后台绑定的模型。 */
    private final SceneModelResolver sceneModelResolver;
    private final JsonUtils json;
    private final IdempotencyStore idempotency;
    private final MealAnalysisSnapshotStore snapshots;

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    /** 与 {@code MealFoodCatalogServiceImpl} 读同一个配置项：重放旧缓存时按当前阈值重算，不信任缓存里的标记。 */
    @Value("${ai.meal.low-confidence-threshold:70}")
    private int lowConfidenceThreshold = 70;

    @Override
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
            IdempotencyStore.Claim claim = idempotency.claim(key);
            if (claim instanceof IdempotencyStore.Claim.Replay replay) {
                MealAnalysisDtos.Result cached = json.fromJson(replay.json(), MealAnalysisDtos.Result.class);
                // 旧缓存不含 needsConfirmation（或为 null），重放时无条件按明细重算，避免旧标记被固化。
                cached.setNeedsConfirmation(needsConfirmation(cached.getItems()));
                return cached;
            }
            if (claim instanceof IdempotencyStore.Claim.InFlight) {
                throw new BizException(ErrorCode.CONFLICT, "相同请求处理中，请稍后重试");
            }
        }
        try {
            // 回显/溯源用的模型编码取自场景解析（场景绑定 → 视觉默认 → yaml 通道兜底），
            // 与下面 vision.analyze(...) 真正走的模型同源；读 yaml 的 ai.meal.model 会写错。
            String model = sceneModelResolver.modelCode(SceneCode.MEAL_ANALYSIS);
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
                MealFoodCatalogService.Resolution resolution = catalog.resolve(food, model, media.getId());
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
                    .newFoodsCount(newCount).warnings(List.copyOf(warnings))
                    .needsConfirmation(needsConfirmation(items)).build();
            issueAnalysisToken(userId, request.getMediaId(), request.getNodeCode(), model, items, result);
            if (key != null) idempotency.complete(key, result);
            return result;
        } catch (RuntimeException ex) {
            if (key != null) idempotency.release(key);
            throw ex;
        }
    }

    /**
     * 生成识别快照令牌并写入 Redis（3b1 只写不读，保存链路自 3b2 起才消费）。
     * <p>快照写入失败<b>不得影响识别结果</b>：令牌置空，前端按「无令牌」处理；识别是主功能，token 是增强项。
     * 幂等重放走缓存分支，不重建快照，因此这里只在真正跑完识别的路径上调用。
     */
    private void issueAnalysisToken(Long userId, Long mediaId, String nodeCode, String model,
                                    List<MealAnalysisDtos.Item> items, MealAnalysisDtos.Result result) {
        String token = newToken();
        MealAnalysisSnapshotStore.Snapshot snapshot = new MealAnalysisSnapshotStore.Snapshot(
                userId, mediaId, nodeCode, System.currentTimeMillis(), model, toSnapshotItems(items));
        if (!snapshots.put(userId, token, snapshot)) {
            log.warn("餐食识别快照不可用，本次返回空 analysisToken：userId={} mediaId={}", userId, mediaId);
            return;
        }
        result.setAnalysisToken(token);
    }

    private static List<MealAnalysisSnapshotStore.SnapshotItem> toSnapshotItems(List<MealAnalysisDtos.Item> items) {
        List<MealAnalysisSnapshotStore.SnapshotItem> list = new ArrayList<>(items.size());
        for (MealAnalysisDtos.Item item : items) {
            list.add(new MealAnalysisSnapshotStore.SnapshotItem(
                    item.getFoodName(), item.getFoodId(), item.getLibraryFoodId(), item.getCategoryCode(),
                    item.getGiValue(), item.getNutritionSource(), item.getLibraryStatus(), item.getConfidence()));
        }
        return list;
    }

    /** 32 字节随机 + Base64URL 无填充（43 字符）；不用 UUID，避免熵不足且无扩展空间。 */
    private static String newToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    /**
     * 按当前阈值重算每条明细的标记，并汇总整餐结论。
     * <p>
     * 明细为空或 null 时恒为 true：空结果最需要用户介入，不能因为 {@code anyMatch} 在空集合上返回 false
     * 而被判定为"无需确认"。注意成功路径上 items 为空会先抛 BizException，这里的空判定是防御性的，
     * 主要服务于幂等重放（旧缓存可能是空 items）。
     */
    boolean needsConfirmation(List<MealAnalysisDtos.Item> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        boolean any = false;
        for (MealAnalysisDtos.Item item : items) {
            int confidence = item.getConfidence() == null ? 0 : item.getConfidence();
            boolean flag = MealFoodCatalogService.needsConfirmation(confidence, item.getLibraryStatus(), lowConfidenceThreshold);
            item.setNeedsConfirmation(flag);
            any = any || flag;
        }
        return any;
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
