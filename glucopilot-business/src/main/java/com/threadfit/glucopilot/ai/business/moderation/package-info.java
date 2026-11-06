/**
 * 内容审核能力：一段文字进，三态判定出。
 *
 * <p>这里只放"和模型打交道"的部分——取提示词、发起调用、解析输出。它<b>不认识</b>文章、评论、社区表，也不知道判定完该发布还是隐藏；
 * 那些是 community 模块的事（{@code CommunityModerationWorker} 只做"出队 → 交给我们 → 写回"的节拍器）。
 *
 * <p>依赖方向固定为 {@code community → ai}：本包不 import 任何业务模块。
 */
package com.threadfit.glucopilot.ai.business.moderation;