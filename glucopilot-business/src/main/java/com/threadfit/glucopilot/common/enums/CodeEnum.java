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
        DISABLED(0), NORMAL(1), CANCELLING(2), CANCELLED(3), CANCELLED_RECOVERABLE(4);
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
        WECHAT(1), PHONE(2), EMAIL(3);
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
        AVATAR(1), GLUCOSE(2), MEAL(3), CHAT_IMAGE(4), CMS(5), CHAT_VOICE(6), ARTICLE_IMAGE(7), ARTICLE_VIDEO(8);
        private final int code;
    }

    /** 饮食饥饱（meal_record.hunger_level）：1饱腹 2一般 3微饿。 */
    @Getter
    @RequiredArgsConstructor
    enum HungerLevel implements CodeEnum {
        FULL(1), NORMAL(2), SLIGHT_HUNGRY(3);
        private final int code;
    }

    /** 文件存储 provider（media_file.storage_provider）：1=MinIO，2=阿里云OSS。 */
    @Getter
    @RequiredArgsConstructor
    enum StorageProvider implements CodeEnum {
        MINIO(1), ALIYUN_OSS(2);
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

    /** chat_session.session_status */
    @Getter
    @RequiredArgsConstructor
    enum ChatSessionStatus implements CodeEnum {
        ARCHIVED(0), ACTIVE(1), ENDED(2);
        private final int code;
    }

    /** chat_session.session_type */
    @Getter
    @RequiredArgsConstructor
    enum ChatSessionType implements CodeEnum {
        TEXT(1), VOICE(2), MIXED(3);
        private final int code;
    }

    /** chat_message.msg_role */
    @Getter
    @RequiredArgsConstructor
    enum ChatMsgRole implements CodeEnum {
        USER(1), ASSISTANT(2), SYSTEM(3);
        private final int code;
    }

    /** chat_message.msg_type */
    @Getter
    @RequiredArgsConstructor
    enum ChatMsgType implements CodeEnum {
        TEXT(1), IMAGE(2), VOICE(3);
        private final int code;
    }

    /** chat_message.finish_reason（DDL CHECK 仅允许 1~4，允许 NULL） */
    @Getter
    @RequiredArgsConstructor
    enum ChatFinishReason implements CodeEnum {
        NORMAL(1), LENGTH(2), CONTENT_FILTER(3), ERROR(4);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum FeedbackType implements CodeEnum {
        SUGGESTION(1), BUG(2), DEMAND(3), OTHER(4);
        private final int code;
    }

    @Getter
    @RequiredArgsConstructor
    enum ReplyStatus implements CodeEnum {
        RECEIVED(0), PROCESSING(1), HANDLED(2), CLOSED(3);
        private final int code;
    }

    /** 界面字号（user_setting.font_size）。 */
    @Getter
    @RequiredArgsConstructor
    enum FontSize implements CodeEnum {
        STANDARD(1), LARGE(2), LARGER(3);
        private final int code;
    }

    /** 界面主题（user_setting.theme_code）。 */
    @Getter
    @RequiredArgsConstructor
    enum ThemeCode implements CodeEnum {
        GREEN(1), TEAL(2), SIMPLE(3);
        private final int code;
    }

    /** 注销模式（user_account_cancellation.mode）。 */
    @Getter
    @RequiredArgsConstructor
    enum CancellationMode implements CodeEnum {
        IMMEDIATE(1), ISOLATE_7D(2);
        private final int code;
    }

    /** 注销状态（user_account_cancellation.status）。 */
    @Getter
    @RequiredArgsConstructor
    enum CancellationStatus implements CodeEnum {
        PENDING(0), RUNNING(1), EXECUTED(2), RECOVERED(3), FAILED(4);
        private final int code;
    }

    /** 注销原因（user_account_cancellation.reason_code，可空）。 */
    @Getter
    @RequiredArgsConstructor
    enum CancellationReason implements CodeEnum {
        NOT_USING(1), PRIVACY(2), EXPERIENCE(3), OTHER(4);
        private final int code;
    }

    /** 注销验证通道（user_account_cancellation.verify_channel）：1短信 2邮箱 3微信(wx.login code 现场验证)。 */
    @Getter
    @RequiredArgsConstructor
    enum CancellationVerifyChannel implements CodeEnum {
        SMS(1), EMAIL(2), WECHAT(3);
        private final int code;
    }
}
