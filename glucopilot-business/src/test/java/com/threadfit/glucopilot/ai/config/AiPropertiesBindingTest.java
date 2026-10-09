package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.core.SceneCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对**真实的 application.yaml** 做一次属性绑定 + 提示词存在性校验。
 * <p>
 * 为什么需要它：AI 模块的启动会遍历全部场景，任一场景没配或提示词文件缺失都会让应用直接起不来；
 * 而这类错误在编译期完全看不出来。这里不启动 Spring 容器（不依赖 MySQL/Redis），
 * 只把 yaml 绑定一遍，等价于提前跑一次「启动前校验」，可以在 CI 里拦住配置事故。
 * <p>
 * 特别地：它同时校验 yaml 里的 key 与 {@link AIProperties} 的字段是否能对上——
 * 项目历史上就出过一次「yaml 写 `ai.prompts.agent-system-file`、代码要
 * `ai.prompts.agent-prompts.agent-system-file`」导致绑定不上、应用启动 NPE 的事故。
 */
class AiPropertiesBindingTest {

    private static AIProperties bindApplicationYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        return binder.bind("ai", Bindable.of(AIProperties.class))
                .orElseThrow(() -> new AssertionError("application.yaml 中缺少 ai.* 配置"));
    }

    @Test
    void 四个场景都应配置且提示词文件存在() throws IOException {
        AIProperties properties = bindApplicationYaml();

        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = properties.scene(scene);
            assertNotNull(config.getPrompt(), "场景 " + scene.getConfigKey() + " 未配置提示词路径");
            assertTrue(new ClassPathResource(config.getPrompt()).exists(),
                    "场景 " + scene.getConfigKey() + " 的提示词文件不存在：" + config.getPrompt());
            assertFalse(config.getVersion() == null || config.getVersion().isBlank(),
                    "场景 " + scene.getConfigKey() + " 未配置提示词版本");
        }
    }

    @Test
    void 医疗安全词与记忆窗口应有有效默认值() throws IOException {
        AIProperties properties = bindApplicationYaml();

        assertTrue(properties.getGuard().isEnabled(), "医疗安全硬拦截默认应开启");
        assertFalse(properties.getGuard().getSensitiveWords().isEmpty(), "敏感词表不能为空，否则护栏形同虚设");
        assertTrue(properties.getMemory().getWindowMessages() > 0, "会话窗口条数必须为正");
        assertTrue(properties.getMemory().getMaxFetch() >= properties.getMemory().getWindowMessages(),
                "单次拉取上限不应小于窗口条数，否则窗口形同被截断");
    }
}
