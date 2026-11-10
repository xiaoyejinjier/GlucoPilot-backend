/**
 * ChatClient 的 Advisor：只服务「多轮对话」这一条链路。
 * <p>
 * 按 {@code getOrder()} 决定顺序：{@code MedicalGuardAdvisor}（医疗硬拦截）→ {@code DynamicPromptAdvisor}（CHAT 场景提示词）→ {@code ChatMemoryReadAdvisor}（历史/摘要/长期记忆装配）。
 *
 * <h2>为什么只有 CHAT 走 advisor，别的场景在调用点写 system</h2>
 * 三个 advisor 各自解决的都是「<b>跨轮次</b>才出现的问题」：硬拦截要在每次请求前看输入，
 * 提示词要能随后台改动即时生效，历史要按 token 预算裁剪。只有对话有这些需求。
 * 另外五个场景（视觉识别、滚动摘要、记忆抽取、内容审核、中英翻译）都是一次性调用：
 * 提示词固定、没有历史、不需要拦截，直接在调用点 {@code .system(...)} 更直白——
 * 给它们也注册 advisor 只会让「提示词到底从哪来」变得难以追踪。
 * 判据：<b>需要跨轮次状态的场景用 advisor，一次性调用在调用点写。</b>
 *
 * <h2>顺序不是各管各的</h2>
 * 消息的最终形态由 {@code DynamicPromptAdvisor}（把提示词放到最前）和
 * {@link com.threadfit.glucopilot.ai.memory.assembly.ChatHistoryAssembler}（拼历史时把提示词留在最前）
 * 共同决定，任一侧单独改动都可能让提示词落到历史之后。所以顺序类断言要串成一条链测，
 * 见 {@code AdvisorChainOrderTest}——分别测两个类是测不出来的（曾经就是这样漏过）。
 */
package com.threadfit.glucopilot.ai.advisor;
