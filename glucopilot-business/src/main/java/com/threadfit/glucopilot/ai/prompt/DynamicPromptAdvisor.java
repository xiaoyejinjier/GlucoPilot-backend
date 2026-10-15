package com.threadfit.glucopilot.ai.prompt;

import com.threadfit.glucopilot.ai.core.SceneCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 每次请求把<b>当前生效</b>的场景系统提示词写进 prompt。
 *
 * <h2>为什么需要它</h2>
 * 提示词进后台（{@code ai_prompt_template}）之后就不能再"启动期烤死"：
 * {@code ChatClient.Builder.defaultSystem(...)} 只在构造 client 时取一次，
 * 后台改完提示词必须重启才生效——那就等于没做后台化。
 * 因此文本 client 不再声明 {@code defaultSystem}，改由本 Advisor 在每次调用前注入。
 *
 * <h2>语义：替换而不是追加</h2>
 * 本 Advisor 会<b>丢弃请求里已有的全部 SystemMessage</b>，再把当前场景提示词放在最前面。
 * 两个原因：
 * <ul>
 *   <li>若只追加，一旦请求里还带着一份旧提示词（例如别处保留了 {@code defaultSystem}、
 *       或调用方自己写了 system），模型会同时看到两段可能互相矛盾的指令——这是最难排查的一类问题：</li>
 *   <li>「谁的系统提示词说了算」必须唯一。场景提示词是唯一的权威来源，
 *       场景特化的措辞应当通过后台改提示词实现，而不是在代码里再叠一层。</li>
 * </ul>
 * 内容与请求里那份完全相同时直接放行，避免每个 token 都白重建一次消息列表。
 *
 * <h2>顺序</h2>
 * 排在会话记忆注入之前（order 更小）：这样无论记忆 Advisor 之后怎么拼装历史，
 * 系统提示词始终是消息列表的第一条。
 */
@Slf4j
public class DynamicPromptAdvisor implements CallAdvisor, StreamAdvisor {

    /** 比记忆注入更靠前，保证系统提示词落在消息列表最前面。 */
    private static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 10;

    private final AiPromptTemplateService prompts;

    /** 本 Advisor 负责的场景（文本 client 用 {@link SceneCode#CHAT}）。 */
    private final SceneCode scene;

    public DynamicPromptAdvisor(AiPromptTemplateService prompts, SceneCode scene) {
        this.prompts = prompts;
        this.scene = scene;
    }

    @Override
    public String getName() {
        return "DynamicPromptAdvisor(" + scene.getCode() + ")";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(withPrompt(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(withPrompt(request));
    }

    /** 包级可见，便于单测直接断言消息列表，不必起 client。 */
    ChatClientRequest withPrompt(ChatClientRequest request) {
        String prompt = prompts.systemPrompt(scene);
        if (!StringUtils.hasText(prompt)) {
            // 服务层保证非空（空提示词在启动期就会失败），这里只是不让空值把请求打崩
            log.warn("场景 {} 当前提示词为空，本次请求不注入系统提示词", scene.getCode());
            return request;
        }
        List<Message> instructions = request.prompt().getInstructions();
        if (alreadySame(instructions, prompt)) {
            return request;
        }
        List<Message> rebuilt = new ArrayList<>(instructions.size() + 1);
        rebuilt.add(new SystemMessage(prompt));
        for (Message message : instructions) {
            if (!(message instanceof SystemMessage)) {
                rebuilt.add(message);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("注入场景提示词 scene={} source={} 消息数 {} → {}",
                    scene.getCode(), prompts.source(scene), instructions.size(), rebuilt.size());
        }
        return request.mutate()
                .prompt(new Prompt(rebuilt, request.prompt().getOptions()))
                .context(request.context())
                .build();
    }

    /** 请求里正好有一条内容相同的 SystemMessage（且没有别的 SystemMessage）时无需重建。 */
    private static boolean alreadySame(List<Message> instructions, String prompt) {
        int systemCount = 0;
        boolean same = false;
        for (Message message : instructions) {
            if (message instanceof SystemMessage systemMessage) {
                systemCount++;
                same = prompt.equals(systemMessage.getText());
            }
        }
        return systemCount == 1 && same;
    }
}
