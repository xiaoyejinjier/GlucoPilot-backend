package com.threadfit.glucopilot.common.config;

import com.threadfit.glucopilot.file.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件的静态体检（不启动 Spring 容器）。
 * <p>
 * 这三条都是「踩过或差点踩到」的坑：
 * <ol>
 *   <li><b>语法可解析</b>：配置写错会导致启动失败，而编译期完全看不出来；</li>
 *   <li><b>同一 profile 不要同时存在 {@code .yml} 与 {@code .yaml}</b>：Spring Boot 会把两个都加载，
 *       同名键的覆盖顺序不确定。项目曾同时存在 {@code application-dev.yaml} 与 {@code application-dev.yml}
 *       （两个人各写了一套），今天恰好键不重叠所以"能用"，但迟早出事；</li>
 *   <li><b>顶层键不重复</b>：YAML 里重复的顶层键会被后者覆盖（或直接报错），
 *       本项目真实发生过一次——往 {@code application.yaml} 追加时又写了一个 {@code spring:}。</li>
 * </ol>
 */
class ConfigFilesTest {

    private static final Path RESOURCES_DIR = Path.of("src/main/resources");

    /** 只匹配列 0 开始的顶层键；注释行（# 开头）与缩进子键都不会命中。 */
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z][A-Za-z0-9_.-]*):");

    @Test
    void 所有配置文件都应能被解析() {
        List<Path> files = configFiles();
        assertFalse(files.isEmpty(), "没找到任何 application*.yaml，工作目录是否不是 backend/？");

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (Path file : files) {
            List<PropertySource<?>> sources;
            try {
                sources = loader.load(file.getFileName().toString(), new FileSystemResource(file));
            } catch (IOException e) {
                throw new AssertionError(file + " 解析失败（YAML 语法错误）", e);
            }
            assertFalse(sources.isEmpty(), file + " 解析结果为空");
        }
    }

    @Test
    void 同一profile不应同时存在yml与yaml两种扩展名() {
        Set<String> bases = new HashSet<>();
        for (Path file : configFiles()) {
            String name = file.getFileName().toString();
            String base = name.replaceAll("\\.ya?ml$", "");
            assertTrue(bases.add(base),
                    "同一 profile 同时存在 .yml 与 .yaml（Spring 会两个都加载、覆盖顺序不确定）：" + name);
        }
    }

    @Test
    void 顶层键不应重复() throws IOException {
        for (Path file : configFiles()) {
            Set<String> seen = new HashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher matcher = TOP_LEVEL_KEY.matcher(line);
                if (matcher.find()) {
                    String key = matcher.group(1);
                    assertTrue(seen.add(key), file + " 顶层键重复：" + key + "（YAML 后者会覆盖前者）");
                }
            }
        }
    }

    /** 与 Spring 的加载范围一致：application 及其 profile 变体。 */
    private static List<Path> configFiles() {
        try (Stream<Path> stream = Files.list(RESOURCES_DIR)) {
            return stream
                    .filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new AssertionError("读取 " + RESOURCES_DIR.toAbsolutePath() + " 失败", e);
        }
    }

    /**
     * 键名与配置类是否对得上。
     * <p>
     * 本项目踩过这类坑：yaml 写 {@code ai.prompts.agent-system-file}、代码要
     * {@code ai.prompts.agent-prompts.agent-system-file}，结果绑定不上、应用启动时 NPE。
     * 这里对对象存储再钉一遍——它的键名（{@code access-key} ↔ {@code accessKey}）极易写错。
     */
    @Test
    void 对象存储配置应能绑定到StorageProperties() throws IOException {
        StorageProperties props = bindFrom("application-dev.yaml", "glucopilot.storage", StorageProperties.class);

        assertEquals("local", props.getProvider(), "开发环境应使用免配置的本地存储");
        assertTrue(StringUtils.hasText(props.getLocalDir()), "glucopilot.storage.local-dir 未绑定");
    }

    private static <T> T bindFrom(String fileName, String prefix, Class<T> type) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(fileName, new FileSystemResource(RESOURCES_DIR.resolve(fileName)));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources))
                .bind(prefix, Bindable.of(type))
                .orElseThrow(() -> new AssertionError(fileName + " 中缺少 " + prefix + " 配置"));
    }
}
