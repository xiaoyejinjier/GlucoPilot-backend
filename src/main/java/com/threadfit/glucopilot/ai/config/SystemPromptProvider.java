package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.utils.PromotesLoader;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 系统提示词提供者：启动时加载一次并缓存，避免每次请求读文件。
 * <p>
 * 提示词文件为空视为配置错误，直接启动失败——避免"静默拿到空系统提示词"，
 * 表现为模型答非所问却查不出原因（本项目已踩过一次：yaml 绑定不上导致读到 null）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptProvider {

    private final AIProperties aiProperties;

    private String source;
    private String text;

    @PostConstruct
    void init() {
        String file = aiProperties.getPrompts().getAgentPrompts().getAgentSystemFile();
        if (!StringUtils.hasText(file)) {
            throw new IllegalStateException(
                    "系统提示词路径未配置，请检查 ai.prompts.agent-prompts.agent-system-file");
        }
        this.source = file;
        this.text = PromotesLoader.loadPromptWithFallback(null, file);
        if (!StringUtils.hasText(this.text)) {
            throw new IllegalStateException("系统提示词内容为空，请检查文件：" + file);
        }
        log.info("系统提示词加载完成：{}（{} 字）", file, this.text.length());
    }

    /** 提示词正文（非空）。 */
    public String text() {
        return text;
    }

    /** 来源路径，用于排障与回显。 */
    public String source() {
        return source;
    }

    /** 正文字数，冒烟接口用它证明提示词确实加载了。 */
    public int chars() {
        return text == null ? 0 : text.length();
    }

    /**
     * 提示词文件名（不含路径），用于落 chat_session.system_prompt_ver。
     * 该列 VARCHAR(32)，故只取 basename。
     */
    public String fileName() {
        if (source == null) {
            return null;
        }
        int slash = source.lastIndexOf('/');
        String name = slash >= 0 ? source.substring(slash + 1) : source;
        return name.length() <= 32 ? name : name.substring(name.length() - 32);
    }
}
