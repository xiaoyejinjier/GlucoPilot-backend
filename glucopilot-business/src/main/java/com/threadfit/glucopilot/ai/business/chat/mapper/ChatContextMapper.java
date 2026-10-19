package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatContextEntity;

/**
 * 分片审计写入（chat_context，③ 期）。
 *
 * <h2>为什么只有 CRUD，一条自写 SQL 都没有</h2>
 * 这张表目前唯一的用途是<b>只写不读</b>的排障与成本分析：写入是一次 {@code insert}（每轮几行、批量插入），
 * 读取是"按会话/轮次捞一段"（{@code session_id, context_round, sort_no} 上已有索引），
 * 两者都是 MyBatis-Plus 的 CRUD 能直接覆盖的形状。手写 SQL 只会多一处"改了实体忘了改 SQL"的机会。
 * <p>
 * <b>刻意不提供任何删除封装</b>：分片元数据与业务数据同生命周期，删会话时该不该跟着删是产品决定；
 * 在它变成决定之前，不给出"顺手删掉"的入口。
 */
public interface ChatContextMapper extends BaseMapper<ChatContextEntity> {
}
