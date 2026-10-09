/**
 * 饮食分析场景（接口 2.20 饮食分析接口 / 场景编码 {@code SceneCode.MEAL_ANALYSIS}）。
 * <p>
 * 现状：<b>待实现</b>。阻塞点是模型能力而非本模块结构——该场景需要多模态（视觉）模型，
 * 而当前唯一 provider（DeepSeek）的请求体里 content 是字符串，不支持图片输入。
 * <p>
 * 依赖与前置：
 * <ul>
 *   <li>视觉 provider 接入（{@code AiChatClientRegistry} 里按 {@code SceneCode.isRequiresVision()} 分流）；</li>
 *   <li>提示词：{@code prompts/meal-analysis/system.md}（已就绪，要求输出固定字段的 JSON）；</li>
 *   <li>结构化输出：用 Spring AI 的 {@code .entity(MealAnalysisResult.class)} 直接映射，
 *       不要手工解析 JSON 字符串；</li>
 *   <li>食物营养库（{@code food_nutrition}）作为估算数据源，属其他模块交付。</li>
 * </ul>
 * 计划落点：{@code controller/service/dto} 三层，对齐接口 2.20 的入参（路径 id）与
 * 出参（predictNow / predictSuggest / suggestText / carbsG），同步写回 {@code meal_record}。
 */
package com.threadfit.glucopilot.ai.business.meal;
