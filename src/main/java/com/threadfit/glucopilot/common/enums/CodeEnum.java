package com.threadfit.glucopilot.common.enums;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 带整型 code 的枚举约定：API 传数字，中文仅 UI。
 */
public interface CodeEnum {

    int getCode();

    static <E extends Enum<E> & CodeEnum> E of(Class<E> type, int code) {
        for (E constant : type.getEnumConstants()) {
            if (constant.getCode() == code) {
                return constant;
            }
        }
        throw new BizException(ErrorCode.BAD_REQUEST, type.getSimpleName() + " 非法值: " + code);
    }

    @Getter
    @RequiredArgsConstructor
    enum AccountStatus implements CodeEnum {
        DISABLED(0), NORMAL(1), CANCELLING(2);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum OpsAccountStatus implements CodeEnum {
        DISABLED(0), NORMAL(1);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum Gender implements CodeEnum {
        UNKNOWN(0), MALE(1), FEMALE(2);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum DiabetesType implements CodeEnum {
        UNSPECIFIED(0), TYPE_1(1), TYPE_2(2), GESTATIONAL(3), OTHER(4);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum LoginType implements CodeEnum {
        WECHAT(1), PHONE(2);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum GlucoseSourceType implements CodeEnum {
        MANUAL(1), OCR(2), DEVICE(3);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum MediaBizType implements CodeEnum {
        AVATAR(1), GLUCOSE(2), MEAL(3), CHAT_IMAGE(4), CMS(5), CHAT_VOICE(6);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum OcrBizType implements CodeEnum {
        GLUCOSE(1), MEAL(2);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum TaskStatus implements CodeEnum {
        PENDING(0), RUNNING(1), SUCCEEDED(2), FAILED(3);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum YesNo implements CodeEnum {
        NO(0), YES(1);
        private final int code;
    }
}
