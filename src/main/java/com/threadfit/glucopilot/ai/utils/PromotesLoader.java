package com.threadfit.glucopilot.ai.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 提示词文件加载工具（classpath 内置默认 + 外部文件覆盖）。
 */
public final class PromotesLoader {

    private PromotesLoader() {
    }

    /**
     * 读取 resources 下的 md 提示词文件为字符串。
     *
     * @param path 类路径，如 promotes/agent/health_agent_system.md
     * @return 完整文本字符串
     */
    public static String loadPrompt(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("提示词 classpath 路径为空");
        }
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在：" + path);
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词文件失败：" + path, e);
        }
    }

    /**
     * 读取外部文件；路径为空或不可读时返回 null，由调用方决定降级策略。
     */
    public static String loadExternalPromptOrNull(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path path = Path.of(filePath);
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 优先读取外部配置文件，不存在/读不到则降级读取 classpath 内置默认 prompt。
     * <p>
     * externalPath 允许为 null（本项目当前只用 classpath），但 classpathPath 必须有效，
     * 否则抛带路径的可读异常——不要吞异常，否则表现为"提示词悄悄没生效"。
     *
     * @param externalPath  外部文件路径，可为 null
     * @param classpathPath classpath 内默认文件路径
     * @return prompt 字符串（非空）
     */
    public static String loadPromptWithFallback(String externalPath, String classpathPath) {
        String external = loadExternalPromptOrNull(externalPath);
        if (external != null && !external.isBlank()) {
            return external;
        }
        return loadPrompt(classpathPath);
    }
}
