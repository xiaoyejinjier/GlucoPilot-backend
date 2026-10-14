package com.threadfit.glucopilot.ai.vision;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

/**
 * 多模态「阶段 1」：把用户发来的图片交给视觉模型，产出一段客观描述。
 * <p>
 * <b>为什么是独立的一步（两阶段）</b>：视觉模型不适合直接对着用户说话——它既要描述图片，
 * 又要遵守医疗红线、引用会话上下文、维持人设，职责会打架；而且图片场景的合规约束
 * （不做诊断、不给剂量）在文本链路上已经有完整实现。所以拆成：
 * <ol>
 *   <li><b>本类</b>：图片 → 客观描述（纯识别，不带人设、不带记忆、不给建议）；</li>
 *   <li>文本模型：描述 + 会话记忆 → 面向用户的回答（复用 {@link SceneCode#CHAT} 的完整链路）。</li>
 * </ol>
 * 这次描述还会被回填进 {@code chat_message.media_caption}，因此它同时承担了
 * 「让图片进入会话记忆」的职责——一次调用同时服务两件事。
 * <p>
 * <b>图片以 base64 随请求体发送</b>（{@code media(MimeType, Resource)} 会被框架编码成 data URI），
 * 而不是把 URL 交给模型服务：开发环境的对象存储地址是 {@code localhost}，第三方根本回连不到；
 * 生产即便公网可达，也等于把用户健康图片的地址暴露给第三方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionAnalyzer {

    /** 阶段 1 的用户指令：与系统提示词（prompts/chat-image/analyze.md）配合，二者缺一不可。 */
    private static final String BASE_INSTRUCTION =
            "请识别这张图片，并按系统提示的要求输出客观描述。";

    /**
     * 视觉调用成功、但内容为空时的兜底描述。
     * <p>
     * 刻意与「调用失败」区别对待：这种情况给一句诚实的话术让阶段 2 有东西可接，
     * 而不是把整轮打成错误——图确实收到了，只是识别不出内容。
     */
    private static final String CAPTION_FALLBACK = "图片已收到，但自动识别没有返回有效内容。";

    private final AiChatClientRegistry chatClientRegistry;
    private final ObjectStorageService objectStorageService;
    private final AIProperties aiProperties;
    private final AiPromptTemplateService promptTemplateService;

    /**
     * 识别图片并返回描述。
     *
     * @param media    已通过归属校验的图片记录
     * @param userText 用户随图片一起发送的文字，可为空
     * @return 客观描述，保证非空
     */
    public String describe(MediaFile media, String userText) {
        String instruction = buildInstruction(userText);
        long startNanos = System.nanoTime();
        String caption;
        try {
            caption = analyze(media, SceneCode.CHAT_IMAGE, instruction);
        } catch (RuntimeException e) {
            // 基础设施类失败（凭据/网络/上游 5xx）不吞：吞掉会让「API 挂了」表现成
            // 「模型说看不懂这张图」，用户按提示换张更清晰的图重试依然失败，而运维侧毫无信号。
            log.error("视觉分析调用失败 mediaId={}", media.getId(), e);
            throw e;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (!StringUtils.hasText(caption)) {
            log.warn("视觉分析返回空内容 mediaId={} 耗时={}ms，改用兜底描述", media.getId(), elapsedMs);
            return CAPTION_FALLBACK;
        }
        log.info("视觉分析完成 mediaId={} 耗时={}ms 描述={}字", media.getId(), elapsedMs, caption.length());
        return caption;
    }

    /**
     * 按业务场景执行一次视觉识别。调用方负责解析结构化结果；本方法只处理图片读取、大小限制和场景提示词。
     */
    public String analyze(MediaFile media, SceneCode scene, String instruction) {
        ChatClient visionClient = chatClientRegistry.visionClient(scene);
        AIProperties.VisionProperties vision = aiProperties.getVision();
        String mimeType = requireImage(media, vision);
        byte[] bytes = readWithinLimit(media, vision);
        ChatClient.ChatClientRequestSpec prompt = visionClient.prompt()
                .system(promptTemplateService.systemPrompt(scene));
        if (scene == SceneCode.MEAL_ANALYSIS) {
            prompt.options(OpenAiChatOptions.builder()
                    .model(aiProperties.getMeal().getModel())
                    .maxCompletionTokens(aiProperties.getMeal().getMaxTokens())
                    .temperature(0.1));
        }
        return prompt
                .user(user -> user
                        .text(instruction)
                        .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(bytes)))
                .call()
                .content();
    }

    /**
     * MIME 与 Key 校验。
     * <p>
     * 复用 file 模块的 {@code MediaFileConstant.isMimeAllowed}，而不是在 AI 模块里
     * 再维护一份图片类型清单——两份清单迟早会不一致，而这类不一致的表现是
     * 「前端能传上去、模型读不了」，很难定位。
     */
    private static String requireImage(MediaFile media, AIProperties.VisionProperties vision) {
        String mimeType = media.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "图片缺少 MIME 类型，无法进行识别");
        }
        if (!MediaFileConstant.isMimeAllowed(MediaBizType.CHAT_IMAGE, mimeType)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "不支持的图片类型：" + mimeType);
        }
        if (!StringUtils.hasText(media.getStorageKey())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "图片缺少存储 Key，无法进行识别");
        }
        return mimeType;
    }

    /**
     * 读取图片字节，并在读之前先用库里的 {@code size_bytes} 拦一道。
     * <p>
     * 顺序很重要：上传上限是 20MB，而视觉识别上限默认 5MB，
     * 若先读进内存再判断，等于为了一张注定被拒的图白占 20MB 堆内存。
     */
    private byte[] readWithinLimit(MediaFile media, AIProperties.VisionProperties vision) {
        long limit = vision.getMaxImageBytes();
        Long declared = media.getSizeBytes();
        if (declared != null && declared > limit) {
            throw new BizException(ErrorCode.BIZ_REJECTED, tooLarge(declared, limit));
        }
        byte[] bytes = objectStorageService.getObject(media.getStorageKey());
        // size_bytes 允许为 NULL，且登记值与实际对象理论上可能不一致，所以读回来再兜一次
        if (bytes.length > limit) {
            throw new BizException(ErrorCode.BIZ_REJECTED, tooLarge(bytes.length, limit));
        }
        return bytes;
    }

    private static String tooLarge(long actual, long limit) {
        return "图片过大（" + (actual / 1024) + "KB），视觉识别上限 " + (limit / 1024) + "KB";
    }

    /**
     * 用户的文字只作为「识别线索」传给视觉模型，并明确要求它不要回答问题。
     * <p>
     * 这一步是必要的：用户发图时经常带一句「这是我今天的早餐」，
     * 缺了它视觉模型可能把重点放在背景上；但若不明确禁止回答，
     * 阶段 1 就会越权去回答用户的问题，破坏两阶段的职责边界。
     */
    private static String buildInstruction(String userText) {
        if (!StringUtils.hasText(userText)) {
            return BASE_INSTRUCTION;
        }
        return BASE_INSTRUCTION
                + "\n\n用户随图片一起发来的文字（仅作为识别线索，用于判断该重点看什么；"
                + "不要回答其中的问题）：" + userText;
    }
}
