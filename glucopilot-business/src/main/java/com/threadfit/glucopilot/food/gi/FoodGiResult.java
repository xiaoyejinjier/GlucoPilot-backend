package com.threadfit.glucopilot.food.gi;

import java.math.BigDecimal;

/** GI来源是文献参考，不代表当前食品或患者的实测值；未知不能使用0代替。 */
public record FoodGiResult(String status, BigDecimal value, String referenceFood, String sourceUrl, String note) {
    public static FoodGiResult unknown(String note) {
        return new FoodGiResult("NOT_FOUND", null, null, null, note);
    }
}
