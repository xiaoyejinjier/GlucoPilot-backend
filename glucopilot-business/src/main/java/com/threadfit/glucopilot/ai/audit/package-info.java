/**
 * AI 审计读模型：把「模型在业务里到底做了什么」摊平成几张只读列表。
 *
 * <h2>为什么单独一包，而不是塞回各业务域</h2>
 * <ul>
 *   <li>这些查询的<b>视角是审计</b>，不是业务：工具调用、长期记忆、滚动摘要、多模态任务
 *       分属 chat / memory / ocr 三个域，若各自在自己的 controller 里开口子，
 *       前端要拼四个不同前缀的接口，权限点也会四散；</li>
 *   <li>全包<b>只读</b>：没有任何写接口。审计面一旦能改数据，就等于给「抹掉证据」开了口子。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * 只查 {@code ai} 模块自己拥有的表（{@code chat_tool_call}、{@code chat_user_memory}、
 * {@code chat_session.context_summary}、{@code ocr_task}）。社区审核命中与中英翻译缓存属于别的模块，
 * 它们的审计接口留在各自的模块里，由前端在同一个「AI 审计」页聚合——这样
 * {@code AiModuleBoundaryTest} 守的「ai 不反向依赖业务模块」才不被破例。
 *
 * <p>权限统一 {@code ai:chat:audit}：能看会话原文的人才有必要看这些派生数据。
 */
package com.threadfit.glucopilot.ai.audit;
