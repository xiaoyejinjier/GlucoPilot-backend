/**
 * ChatClient 的 Advisor：只服务「多轮对话」这一条链路。
 * <p>
 * 按 {@code getOrder()} 决定顺序：{@code MedicalGuardAdvisor}（医疗硬拦截）→ {@code ChatMemoryReadAdvisor}（历史/摘要/长期记忆装配）。
 *
 * <h2>为什么这里只剩两个顾问</h2>
 * 它们解决的都是「<b>跨轮次</b>才出现的问题」：硬拦截要在每次请求前看输入，历史要按 token 预算裁剪。
 * 另外五个场景（视觉识别、滚动摘要、记忆抽取、内容审核、中英翻译）都是一次性调用：
 * 提示词固定、没有历史、不需要拦截，直接在调用点 {@code .system(...)} 更直白。
 * 判据：<b>需要跨轮次状态的场景用 advisor，一次性调用在调用点写。</b>
 * <p>
 * 对话的<b>系统提示词不再走 advisor</b>：它没有跨轮次状态，只是「当前生效的那一版」，
 * 所以由唯一调用点 {@code ChatCompletionServiceImpl} 每轮读一次、请求级 {@code .system(...)} 注入。
 * 早先那版 {@code DynamicPromptAdvisor} 的额外价值只有「丢弃已有 SystemMessage 再放一条」，
 * 属于不报错的隐式契约——代价是多一个必须与装配器协同的顺序常量，收益不足以支撑。
 *
 * <h2>顺序不是各管各的</h2>
 * 消息的最终形态由「调用点注入的 {@code SystemMessage}」（在消息列表第一条）和
 * {@link com.threadfit.glucopilot.ai.memory.assembly.ChatHistoryAssembler}（拼历史时把这条提示词
 * 从本轮新增消息里拆出来、留在最前）共同决定。任一侧单独改动都可能让提示词落到历史之后，
 * 所以顺序类断言必须把「已带 system 的请求」整条喂进去测，
 * 见 {@code AdvisorChainOrderTest}——分别测调用点与装配器是测不出来的（曾经就是这样漏过）。
 */
package com.threadfit.glucopilot.ai.advisor;
