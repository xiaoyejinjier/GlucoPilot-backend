package com.threadfit.glucopilot.ai.memory.service;

import com.threadfit.glucopilot.ai.memory.dto.AdminChatMemoryDtos;

/**
 * 管理端「用户 AI 长期记忆」契约（4.14 查询 / 4.15 删除）。
 *
 * <h2>为什么要给运营一个只看/只删的入口</h2>
 * 记忆跨会话生效：用户在小程序里只能看到本会话，看不到系统对他的画像，更看不到一条记错的偏好
 * 如何在之后每一轮回答里被引用。因此合规上必须能回答两个问题：系统记住了这个用户什么（4.14）、
 * 能不能让系统彻底忘掉（4.15）。
 *
 * <h2>实现必须守住的语义</h2>
 * <ul>
 *   <li><b>读写都留痕</b>：查询与删除各写一条 {@code ops_audit_log}（模块 {@code ai}）——
 *       只记"谁删了"而不记"谁看了"，等于默许"看完不留痕"，而泄露主要发生在"看"这一侧；</li>
 *   <li><b>审计详情只放结构信息</b>（{@code {"count":N}}），<b>绝不放 memory_text 正文</b>：
 *       否则用户删除记忆之后，正文仍留在审计表里，删除权当场失效；</li>
 *   <li><b>"用户没有记忆"不是错误</b>：查询返回空列表、删除返回 0 条，都是幂等成功；</li>
 *   <li>分页缺省 20 / 上限 100，越界<b>夹紧</b>不报错。</li>
 * </ul>
 */
public interface AdminChatMemoryService {

    /** 4.14 查询某用户的长期记忆（只读未删除，按最近更正优先排序）。 */
    AdminChatMemoryDtos.MemoryList list(Long userId, Integer pageNum, Integer pageSize);

    /** 4.15 逻辑删除某用户全部长期记忆，返回真实删除条数（0 = 本来就没有）。 */
    AdminChatMemoryDtos.DeleteResult deleteAll(Long userId);
}
