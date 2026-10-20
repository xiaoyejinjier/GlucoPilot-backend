/**
 * 饮食分析场景（接口 2.20 饮食分析接口 / 场景编码 {@code SceneCode.MEAL_ANALYSIS}）。
 * <p>
 * 餐食照片经千问通用视觉模型识别，再精确匹配食品库；已知食品使用库中营养，
 * 新食品作为模型估算条目入库待审核。识别结果由用户确认份量后保存饮食快照。
 * <p>
 * 依赖与前置：
 * <ul>
 *   <li>视觉 provider 接入（{@code AiChatClientRegistry} 里按 {@code SceneCode.isRequiresVision()} 分流）；</li>
 *   <li>提示词：{@code prompts/meal-analysis/system.md}（已就绪，要求输出固定字段的 JSON）；</li>
 *   <li>结构化输出解析与校验：{@link com.threadfit.glucopilot.ai.business.meal.service.MealAnalysisService}；</li>
 *   <li>食物营养库（{@code food_nutrition}）作为估算数据源，属其他模块交付。</li>
 * </ul>
 * 入口为 {@code POST /api/v1/meal-analysis}。识别不生成血糖预测、不写饮食记录；
 * 实测当前血糖由用户提供，预测与保存继续使用 prediction 和 meal 的既有接口。
 */
package com.threadfit.glucopilot.ai.business.meal;
