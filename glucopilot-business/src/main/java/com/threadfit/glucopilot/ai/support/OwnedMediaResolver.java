package com.threadfit.glucopilot.ai.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 「媒体引用必须是当前用户自己的」这条访问规则的<b>唯一实现</b>。
 * <p>
 * <b>为什么单独抽出来</b>：它原本在会话链路里被写了两遍——
 * 2.24 写消息时校验一次（拒绝非法引用），2.25 读图之前又校验一次（真正的字节读取入口），
 * 两处的查询条件相同、文案却各写各的（一处是拼接的「图片文件不存在或不可用」，
 * 另一处是硬编码的同一句话）。这种重复的危险不在于行数，而在于
 * <b>将来只改一处</b>：比如某天要求「已删除的媒体也要给出明确提示」，
 * 改了 2.24 忘了 2.25，就会出现「发消息时被拒、但直接调补全却能读到别人的图」这种半修状态。
 * <p>
 * <b>为什么放在 ai/support 而不是 file 模块</b>：{@code MediaFileService.findOwnedMedia}
 * 已经是 file 模块提供的原子查询（一条 SQL 同时校验 userId + bizType + 可用状态），
 * 本类只是在其上加「查不到就抛业务异常」和「文案」这两件属于调用方的事。
 * 把它塞进 file 模块的接口，等于让 file 模块替调用方决定报错话术。
 * <p>
 * <b>为什么文案不区分「不存在」与「不是你的」</b>：区分开就等于提供了一个探测接口——
 * 攻击者可以靠报错差异枚举出他人 mediaId 是否存在。因此两种情形统一为「不存在或不可用」。
 */
@Component
@RequiredArgsConstructor
public class OwnedMediaResolver {

    private final MediaFileService mediaFileService;

    /**
     * 取当前用户拥有的、业务类型匹配的、可用的媒体。
     * <p>
     * 必填性校验由调用方负责（2.24 由 {@code validatePayload} 给出更准确的参数错误），
     * 这里对 {@code null} 也返回同一条「不存在或不可用」，避免把空值当成拥有来处理。
     *
     * @param mediaId  媒体 ID，可为 null（会直接判为不可用）
     * @param userId   当前登录用户
     * @param bizType  期望的业务类型，如 {@link MediaBizType#CHAT_IMAGE}
     * @return 归属确认无误的媒体记录
     * @throws BizException 查不到或归属不符
     */
    public MediaFile require(Long mediaId, Long userId, MediaBizType bizType) {
        MediaFile media = mediaId == null ? null : mediaFileService.findOwnedMedia(mediaId, userId, bizType);
        if (media == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, label(bizType) + "文件不存在或不可用");
        }
        return media;
    }

    /** 面向前端的名称。switch 覆盖全部枚举值，新增业务类型时编译期就会提醒补文案。 */
    private static String label(MediaBizType bizType) {
        return switch (bizType) {
            case AVATAR -> "头像";
            case GLUCOSE -> "血糖照片";
            case MEAL -> "餐食照片";
            case CHAT_IMAGE -> "图片";
            case CHAT_VOICE -> "语音";
            case CMS -> "内容图片";
            case ARTICLE_IMAGE -> "文章配图";
            case ARTICLE_VIDEO -> "文章视频";
        };
    }
}
