package com.threadfit.glucopilot.prediction.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.prediction.config.PredictionProperties;
import com.threadfit.glucopilot.prediction.dto.PredictionDtos;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 餐后血糖预测（纯计算，不落库、不查血糖/饮食记录）。
 * 唯一服务端读 = {@link HealthProfileService#healthViewOf}；算法参数见 {@link PredictionProperties}。
 */
@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final String ALGORITHM_VERSION = "v1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal GLUCOSE_MIN = new BigDecimal("0.1");
    private static final BigDecimal GLUCOSE_MAX = new BigDecimal("39.9");
    private static final int DIVIDE_SCALE = 6;

    // food_category.id 1-9 映射（V2__init_seed.sql:76-79）
    private static final int STAPLE = 1;   // 主食
    private static final int FRUIT = 4;    // 水果
    private static final int MILK = 5;     // 奶
    private static final int BEAN = 6;     // 豆制品
    private static final int NUT = 7;      // 坚果
    private static final int DRINK = 8;    // 饮料
    private static final int SNACK = 9;    // 零食

    private final PredictionProperties props;
    private final HealthProfileService healthProfileService;

    public PredictionDtos.PredictResult predict(Long userId, PredictionDtos.PredictRequest request) {
        if (!GlucoseNodes.isMealNode(request.getNodeCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "nodeCode 必须为餐点节点");
        }
        HealthView health = healthProfileService.healthViewOf(userId);
        return compute(request, health);
    }

    private PredictionDtos.PredictResult compute(PredictionDtos.PredictRequest req, HealthView health) {
        BigDecimal cur = req.getCurrentGlucose();
        BigDecimal carbs = req.getCarbsG();
        BigDecimal portion = req.getPortionRatio() == null ? ONE : req.getPortionRatio();
        boolean med = Boolean.TRUE.equals(req.getMedicationTaken());
        boolean ex = Boolean.TRUE.equals(req.getExerciseDone());

        BigDecimal r = rFor(health.getDiabetesType());
        BigDecimal dMed = med ? props.getMedEffect() : ZERO;
        BigDecimal dEx = ex ? props.getExerciseEffect() : ZERO;
        BigDecimal dCarb = carbs.multiply(portion).multiply(r);

        BigDecimal predictNow = roundGlucose(cur.add(dCarb).add(dMed).add(dEx));

        BigDecimal target = targetOf(health);
        BigDecimal suggestCarbs;
        if (cur.compareTo(props.getDangerHigh()) >= 0) {
            suggestCarbs = ZERO;
        } else {
            BigDecimal raw = target.subtract(cur).subtract(dMed).subtract(dEx)
                    .divide(r, DIVIDE_SCALE, RoundingMode.HALF_UP);
            suggestCarbs = clamp(raw, ZERO, props.getCarbsSuggestMax());
        }

        BigDecimal predictSuggest = roundGlucose(cur.add(suggestCarbs.multiply(r)).add(dMed).add(dEx));

        return PredictionDtos.PredictResult.builder()
                .predictNow(predictNow)
                .suggestText(buildSuggestText(cur, suggestCarbs, carbs, req.getItems()))
                .predictSuggest(predictSuggest)
                .algorithmVersion(ALGORITHM_VERSION)
                .build();
    }

    private BigDecimal rFor(Integer diabetesType) {
        int t = diabetesType == null ? 0 : diabetesType;
        return switch (t) {
            case 1 -> props.getRType1();
            case 2 -> props.getRType2();
            case 3 -> props.getRType3();
            case 4 -> props.getRType4();
            default -> props.getRType0();
        };
    }

    private BigDecimal targetOf(HealthView health) {
        if (health.getTargetPostprandial() != null) {
            return health.getTargetPostprandial();
        }
        if (health.getTargetFasting() != null) {
            return health.getTargetFasting();
        }
        return props.getDefaultTarget();
    }

    private String buildSuggestText(BigDecimal cur, BigDecimal suggestCarbs, BigDecimal totalCarbs,
                                    List<PredictionDtos.Item> items) {
        if (cur.compareTo(props.getDangerHigh()) >= 0) {
            return "当前血糖偏高，建议暂缓进食并继续监测，必要时就医";
        }
        if (suggestCarbs.compareTo(ZERO) <= 0) {
            return "当前血糖已接近或高于目标，建议本餐以蔬菜与蛋白为主、减少或不用主食";
        }
        if (suggestCarbs.compareTo(props.getCarbsSuggestMax()) >= 0) {
            int rice = gramsInt(suggestCarbs.divide(props.getRiceCarbRatio(), DIVIDE_SCALE, RoundingMode.HALF_UP));
            return "目标所需碳水较高，建议按单餐上限 " + gramsInt(props.getCarbsSuggestMax())
                    + " g 用餐（约米饭 " + rice + " g），避免一次过量";
        }
        boolean hasItems = items != null && !items.isEmpty();
        if (!hasItems) {
            int carbsInt = gramsInt(suggestCarbs);
            int rice = gramsInt(suggestCarbs.divide(props.getRiceCarbRatio(), DIVIDE_SCALE, RoundingMode.HALF_UP));
            return "建议本餐摄入碳水约 " + carbsInt + " g（约米饭 " + rice + " g）";
        }
        return buildFoodSuggest(items, suggestCarbs, totalCarbs);
    }

    /**
     * 分食物建议：保量档（肉蛋 2 / 蔬菜 3）不动；主缩档（饮料 8 / 零食 9）优先削减（三段式语气）；
     * 剩余削减按均衡档（主食 1 / 水果 4 / 奶 5 / 豆制品 6 / 坚果 7）原 carbsG 占比等比分摊，主食保底克重。
     */
    private String buildFoodSuggest(List<PredictionDtos.Item> items, BigDecimal suggestCarbs, BigDecimal totalCarbs) {
        BigDecimal delta = totalCarbs.subtract(suggestCarbs);
        if (delta.compareTo(ZERO) <= 0) {
            StringJoiner j = new StringJoiner(" · ");
            for (PredictionDtos.Item it : items) {
                j.add(text(it.getFoodName(), "正常"));
            }
            return j.toString();
        }

        List<PredictionDtos.Item> main = new ArrayList<>();
        List<PredictionDtos.Item> balance = new ArrayList<>();
        for (PredictionDtos.Item it : items) {
            int c = it.getCategoryCode();
            if (isMainReduce(c)) {
                main.add(it);
            } else if (isBalanced(c)) {
                balance.add(it);
            }
        }

        BigDecimal mainCarbs = sumCarbs(main);
        BigDecimal mainCut = delta.min(mainCarbs);
        BigDecimal remain = delta.subtract(mainCut);

        String tone;
        BigDecimal halfMain = mainCarbs.divide(TWO, DIVIDE_SCALE, RoundingMode.HALF_UP);
        if (delta.compareTo(halfMain) <= 0) {
            tone = "减半";
        } else if (delta.compareTo(mainCarbs) <= 0) {
            tone = "建议替换为无糖/少糖";
        } else {
            tone = "不建议";
        }

        String[] balancedClauses = null;
        if (remain.compareTo(ZERO) > 0) {
            balancedClauses = allocateBalanced(balance, remain);
            if (balancedClauses == null) {
                return "本次碳水整体偏高，建议整体减量或分餐食用";
            }
        }

        StringJoiner joiner = new StringJoiner(" · ");
        int bi = 0;
        for (PredictionDtos.Item it : items) {
            int c = it.getCategoryCode();
            if (isMainReduce(c)) {
                joiner.add(text(it.getFoodName(), tone));
            } else if (isBalanced(c)) {
                joiner.add(balancedClauses[bi++]);
            } else {
                joiner.add(text(it.getFoodName(), "正常"));
            }
        }
        return joiner.toString();
    }

    /**
     * 均衡档按原 carbsG 占比等比削减；主食类建议克重保底 stapleFloor。
     * 返回每项建议文案（按 balance 顺序）；削不完返回 null（整体降级）。
     */
    private String[] allocateBalanced(List<PredictionDtos.Item> balance, BigDecimal remain) {
        int n = balance.size();
        BigDecimal[] carbs = new BigDecimal[n];
        BigDecimal[] weight = new BigDecimal[n];
        BigDecimal[] maxCut = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            carbs[i] = orZero(balance.get(i).getCarbsG());
            weight[i] = orZero(balance.get(i).getWeightG());
        }
        for (int i = 0; i < n; i++) {
            int c = balance.get(i).getCategoryCode();
            if (c == STAPLE) {
                if (weight[i].compareTo(ZERO) > 0 && weight[i].compareTo(props.getStapleFloor()) > 0) {
                    maxCut[i] = carbs[i].multiply(weight[i].subtract(props.getStapleFloor()))
                            .divide(weight[i], DIVIDE_SCALE, RoundingMode.HALF_UP);
                } else {
                    maxCut[i] = ZERO; // 主食已到/低于下限或无量，不可再减
                }
            } else {
                maxCut[i] = carbs[i];
            }
        }

        BigDecimal totalCuttable = sumArray(maxCut);
        if (totalCuttable.compareTo(ZERO) <= 0 || totalCuttable.compareTo(remain) < 0) {
            return null;
        }

        BigDecimal[] cut = new BigDecimal[n];
        boolean[] saturated = new boolean[n];
        BigDecimal rem = remain;
        for (int pass = 0; pass < n; pass++) {
            BigDecimal sumC = ZERO;
            for (int i = 0; i < n; i++) {
                if (!saturated[i]) {
                    sumC = sumC.add(carbs[i]);
                }
            }
            if (sumC.compareTo(ZERO) <= 0) {
                break;
            }
            boolean anySaturated = false;
            for (int i = 0; i < n; i++) {
                if (saturated[i]) {
                    continue;
                }
                BigDecimal share = rem.multiply(carbs[i]).divide(sumC, DIVIDE_SCALE, RoundingMode.HALF_UP);
                if (share.compareTo(maxCut[i]) >= 0) {
                    cut[i] = maxCut[i];
                    saturated[i] = true;
                    rem = rem.subtract(maxCut[i]);
                    anySaturated = true;
                }
            }
            if (!anySaturated) {
                for (int i = 0; i < n; i++) {
                    if (!saturated[i]) {
                        cut[i] = rem.multiply(carbs[i]).divide(sumC, DIVIDE_SCALE, RoundingMode.HALF_UP);
                    }
                }
                rem = ZERO;
                break;
            }
        }

        String[] clauses = new String[n];
        for (int i = 0; i < n; i++) {
            String name = balance.get(i).getFoodName();
            if (carbs[i].compareTo(ZERO) <= 0 || cut[i].compareTo(ZERO) <= 0) {
                clauses[i] = text(name, "正常");
            } else if (weight[i].compareTo(ZERO) > 0) {
                BigDecimal gram = carbs[i].subtract(cut[i]).multiply(weight[i])
                        .divide(carbs[i], 0, RoundingMode.HALF_UP);
                clauses[i] = text(name, gram.stripTrailingZeros().toPlainString() + "g");
            } else {
                clauses[i] = text(name, "减少至约 " + gramsInt(carbs[i].subtract(cut[i])) + "g 碳水");
            }
        }
        return clauses;
    }

    private boolean isMainReduce(int c) {
        return c == DRINK || c == SNACK;
    }

    private boolean isBalanced(int c) {
        return c == STAPLE || c == FRUIT || c == MILK || c == BEAN || c == NUT;
    }

    private BigDecimal sumCarbs(List<PredictionDtos.Item> items) {
        BigDecimal sum = ZERO;
        for (PredictionDtos.Item it : items) {
            sum = sum.add(orZero(it.getCarbsG()));
        }
        return sum;
    }

    private static BigDecimal sumArray(BigDecimal[] arr) {
        BigDecimal sum = ZERO;
        for (BigDecimal v : arr) {
            sum = sum.add(v);
        }
        return sum;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static String text(String foodName, String clause) {
        return foodName + " " + clause;
    }

    private static int gramsInt(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal roundGlucose(BigDecimal v) {
        return clamp(v, GLUCOSE_MIN, GLUCOSE_MAX).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v.compareTo(lo) < 0) {
            return lo;
        }
        if (v.compareTo(hi) > 0) {
            return hi;
        }
        return v;
    }
}