package com.threadfit.glucopilot.ai.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跨会话长期记忆（对应 chat_user_memory）。
 *
 * <h2>为什么用 {@link BaseEntity} 而不是只追加基类</h2>
 * 长期记忆是"可更正的事实"：同一个 {@code memory_key}（如 diet_taboo）被新信息推翻时走覆盖写，
 * 用户行使删除权时走逻辑删除——两件事都需要 updated_by/gmt_modified 与 deleted_by/deleted_at 可追溯。
 * 逻辑删除列由基类的 {@code @TableLogic} 提供，因此所有查询都会自动带上 {@code is_deleted = 0}，
 * 业务 SQL 里<b>不应再手写一遍这个条件</b>（写重了不报错，但两份条件漂移时会出现"看起来删了却还在用"）。
 *
 * <h2>唯一键与"删掉同一条键之后还能再写"</h2>
 * 唯一键是 {@code uk_user_key_active(user_id, memory_key, is_deleted)}：生效行为 0、已删行为 1，
 * 于是"同一把键的有效记忆至多一条"和"逻辑删除后可以再写一条新的"同时成立（见
 * {@code ChatUserMemoryMapper#logicDeleteAllByUser} 的说明）。也正因为如此，
 * <b>这里不能改成物理删除</b>：删掉的记忆要能解释"系统曾经记过什么"，这正是合规审计要看的东西。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_user_memory")
public class ChatUserMemoryEntity extends BaseEntity {

    private Long userId;

    /** 记忆键，如 diet_taboo；取值受限于写入侧的白名单（见 UserChatMemoryService）。 */
    private String memoryKey;

    /** 记忆正文（varchar(512)）。 */
    private String memoryText;

    /** 来源会话 ID，可空（用户自助添加的记忆没有来源会话）。 */
    private Long sourceSessionId;

    /** 置信度 0~100（DDL 有 CHECK）；仅作展示与注入排序用，不作为可信度承诺。 */
    private Integer confidence;
}
