package com.threadfit.glucopilot.common.constant;

import java.util.Set;

/**
 * 九时段节点编码（与 dict_glucose_node 种子一致）。
 * 血糖写入要求 is_meal=0；饮食写入要求 is_meal=1。
 */
public final class GlucoseNodes {

    public static final String BEFORE_BREAKFAST = "before_breakfast";
    public static final String BREAKFAST = "breakfast";
    public static final String AFTER_BREAKFAST = "after_breakfast";
    public static final String BEFORE_LUNCH = "before_lunch";
    public static final String LUNCH = "lunch";
    public static final String AFTER_LUNCH = "after_lunch";
    public static final String BEFORE_DINNER = "before_dinner";
    public static final String DINNER = "dinner";
    public static final String AFTER_DINNER = "after_dinner";

    public static final Set<String> MEAL_NODES = Set.of(BREAKFAST, LUNCH, DINNER);

    public static final Set<String> GLUCOSE_NODES = Set.of(
            BEFORE_BREAKFAST, AFTER_BREAKFAST,
            BEFORE_LUNCH, AFTER_LUNCH,
            BEFORE_DINNER, AFTER_DINNER
    );

    private GlucoseNodes() {
    }

    public static boolean isMealNode(String nodeCode) {
        return MEAL_NODES.contains(nodeCode);
    }

    public static boolean isGlucoseNode(String nodeCode) {
        return GLUCOSE_NODES.contains(nodeCode);
    }
}
