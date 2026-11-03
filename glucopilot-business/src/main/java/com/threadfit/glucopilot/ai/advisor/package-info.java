/**
 * ChatClient 的 Advisor。
 * <p>
 * 按 {@code getOrder()} 决定顺序：{@code MedicalGuardAdvisor}（医疗硬拦截）→ {@code DynamicPromptAdvisor}（CHAT 场景提示词）→ {@code ChatMemoryReadAdvisor}（历史/摘要/长期记忆装配）。
 * <p>
 * 所有 advisor 集中在这一个包：加一个 advisor 只有唯一答案，不必在 guard/ prompt/ memory/ 之间挑。
 */
package com.threadfit.glucopilot.ai.advisor;
