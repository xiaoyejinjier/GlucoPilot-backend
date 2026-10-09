package com.threadfit.glucopilot.file.constant;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;

import java.util.Map;
import java.util.Set;

/**
 * 媒体文件常量：大小上限、按 bizType 的 MIME 白名单、MIME→扩展名映射。
 * <p>校验顺序约定：大小 → MIME（见迁移计划 §3.1）；校验逻辑由 Service 调用，抛 {@code BizException(40000)}。
 */
public final class MediaFileConstant {

    /** 默认单文件大小上限（20MB）。 */
    public static final long DEFAULT_MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 图片类 MIME 白名单。 */
    private static final Set<String> IMAGE_MIMES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    /** 语音类 MIME 白名单。 */
    private static final Set<String> AUDIO_MIMES =
            Set.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/aac", "audio/x-m4a");

    /** MIME → 小写扩展名（缺省回退 bin）。 */
    private static final Map<String, String> EXT_BY_MIME = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/gif", "gif"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/aac", "aac"),
            Map.entry("audio/x-m4a", "m4a")
    );

    private MediaFileConstant() {
    }

    /** 语音类业务（CHAT_VOICE=6）走音频白名单，其余走图片白名单。 */
    public static boolean isMimeAllowed(MediaBizType bizType, String mimeType) {
        if (mimeType == null) {
            return false;
        }
        if (bizType == MediaBizType.CHAT_VOICE) {
            return AUDIO_MIMES.contains(mimeType.toLowerCase());
        }
        return IMAGE_MIMES.contains(mimeType.toLowerCase());
    }

    /** 由 MIME 推导存储扩展名；未知 MIME 回退 bin。 */
    public static String extOf(String mimeType) {
        if (mimeType == null) {
            return "bin";
        }
        return EXT_BY_MIME.getOrDefault(mimeType.toLowerCase(), "bin");
    }
}