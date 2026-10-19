package com.threadfit.glucopilot.device.adapter.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class UnitConversion {

    private static final BigDecimal MGDL_TO_MMOL = new BigDecimal("0.0555");

    private UnitConversion() {
    }

    public static BigDecimal toMmolL(BigDecimal value, String unit) {
        if (value == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "value 不能为空");
        }
        if (!StringUtils.hasText(unit)
                || "MMOL_L".equalsIgnoreCase(unit)
                || "mmol/L".equalsIgnoreCase(unit)
                || "mmol_l".equalsIgnoreCase(unit)) {
            return value;
        }
        if ("MG_DL".equalsIgnoreCase(unit)
                || "mg/dL".equalsIgnoreCase(unit)
                || "mg_dl".equalsIgnoreCase(unit)) {
            return value.multiply(MGDL_TO_MMOL).setScale(1, RoundingMode.HALF_UP);
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不支持的单位: " + unit);
    }
}
