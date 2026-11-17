package com.threadfit.glucopilot.ai.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;

/**
 * 跨会话长期记忆（对应 chat_user_memory）。
 *
 * <h2>为什么用 {@link BaseEntity} 而不是只追加基类</h2>
 * 长期记忆是"可更正的事实"：同一个 {@code memory_key}（如 diet_taboo）被新信息推翻时走覆盖写，
 * 用户行使删除权时走逻辑删除——两件事都需要 updated_by/gmt_modified 与 deleted_by/deleted_at 可追溯。
 * 逻辑删除列由基类的 {@code @TableLogic} 提供，因此所有查询都会自动带上 {@code is_deleted = 0}，
 * 业务 SQL 里<b>不应再手写一遍这个条件</b>（写重了不报错，但两份条件漂移时会出现"看起来删了却还在用"）。
 *
 * <h2>唯一键与"删掉同一句话之后还能再写"</h2>
 * 唯一键是 {@code uk_user_key_item(user_id, memory_key, item_hash, is_deleted)}：
 * 类别之下还有条目，{@code item_hash} 是正文规范化后的 SHA-256，条目的身份是「哪句话」而不是「哪个类」。
 * 生效行 {@code is_deleted=0}、已删行 {@code is_deleted=1}，两者能共存，
 * 因此"删掉一条之后同类还能继续记"与"删掉的那句不会再被写回来"同时成立。
 * <b>这里不能改成物理删除</b>：删除态要能解释"系统曾经记过什么"，冷却闸门也依赖 {@code deleted_at}
 * （墓碑的清理交给删除入口，见 {@code ChatUserMemoryMapper} 顶部说明）。
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

    /**
     * 正文规范化后的 SHA-256（V71）：条目的身份，参与唯一键。
     * <p>
     * 为什么不直接拿 {@code memory_text} 做唯一键：正文列 varchar(512) 全列进索引会超长，
     * 而且大小写与空白差异会把"同一句话"判成两条。
     */
    private String itemHash;

    /** 来源会话 ID，可空（用户自助添加的记忆没有来源会话）。 */
    private Long sourceSessionId;

    /** 置信度 0~100（DDL 有 CHECK）；仅作展示与注入排序用，不作为可信度承诺。 */
    private Integer confidence;
}
