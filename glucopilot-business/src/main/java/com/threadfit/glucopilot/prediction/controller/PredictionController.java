package com.threadfit.glucopilot.prediction.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 餐后血糖预测已于阶段 4 下线。
 *
 * <p>路由保留两个版本作为过渡期，固定返回 HTTP 410 / {@code code=41000}；旧客户端据此隐藏预测入口。
 * 过渡期结束后连同 {@code prediction} 包一并删除。
 */
@RestController
@RequestMapping("/api/v1")
public class PredictionController {

    /** 预测餐后血糖（已下线）。 */
    @PostMapping("/predictions/meal-glucose")
    public ApiResponse<Void> predict() {
        throw new BizException(ErrorCode.FEATURE_REMOVED, "餐后血糖预测已下线，请使用餐食升糖负担分析");
    }
}
