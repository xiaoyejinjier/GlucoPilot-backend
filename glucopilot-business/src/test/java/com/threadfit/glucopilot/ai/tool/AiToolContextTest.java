package com.threadfit.glucopilot.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AiToolContext} 的取值边界。
 * <p>
 * 这里覆盖的是「toolContext 存在但缺少 userId」这一类；而「整个 toolContext 都没传」的情况
 * 由框架自己拦截（{@code IllegalArgumentException: ToolContext is required by the method as an argument}），
 * 见 {@code AiToolCallingWiringTest#缺toolContext时框架应明确报错而不是静默失败}。
 */
class AiToolContextTest {

    @Test
    void 应能取出Long形式的用户ID() {
        assertThat(AiToolContext.requireUserId(new ToolContext(Map.of(AiToolContext.USER_ID, 7L))))
                .isEqualTo(7L);
    }

    @Test
    void 应能取出字符串形式的用户ID() {
        // Long 在本项目里统一序列化为字符串，注入口径不一定，两种都要能接住
        assertThat(AiToolContext.requireUserId(new ToolContext(Map.of(AiToolContext.USER_ID, "7"))))
                .isEqualTo(7L);
    }

    @Test
    void 上下文里缺少userId时应抛异常而不是当成0() {
        // 若把缺失当成 0 或 null 放过去，工具就会去查一个不存在的用户，
        // 表现为"查不到数据"而不是"代码写错了"——那是最难查的一类问题
        assertThatThrownBy(() -> AiToolContext.requireUserId(new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AiToolContext.USER_ID);
    }

    @Test
    void 上下文本身为null时应抛异常() {
        assertThatThrownBy(() -> AiToolContext.requireUserId(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AiToolContext.USER_ID);
    }

    @Test
    void 非法数字应抛异常并带上原值() {
        assertThatThrownBy(() -> AiToolContext.requireUserId(
                new ToolContext(Map.of(AiToolContext.USER_ID, "abc"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abc");
    }
}
