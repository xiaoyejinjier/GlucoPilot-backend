package com.threadfit.glucopilot.ai.prompt.service;

import com.threadfit.glucopilot.ai.prompt.dto.AdminAiPromptDtos;

import java.util.List;

/**
 * 后台提示词管理契约（接口 4.11 查询 / 4.12 新增与编辑 / 4.13 启用，外加删除草稿）。
 *
 * <h2>为什么「保存」与「启用」是两个动作</h2>
 * 提示词是线上回答的唯一权威来源：直接改生效版本没有回滚路，也没有"先看看再上"的余地。
 * 因此新增/编辑默认落在<b>草稿</b>，运营确认后再 {@link #activate(Long)}；
 * 编辑生效中的版本一律<b>追加新版本</b>，旧版本留在列表里可回滚。
 *
 * <h2>实现必须守住的语义</h2>
 * <ul>
 *   <li>保存前<b>三道阻断式校验</b>：长度下限 → 模板可渲染（花括号必须是已声明变量）→ 安全关键词；</li>
 *   <li>同场景<b>只有一个启用版本</b>（MySQL 没有部分唯一索引，由实现里"停用其它版本 + 启用自己"同事务保证）；</li>
 *   <li>写操作走 {@code config_version} 乐观锁，冲突返回 409 而不是覆盖他人改动；</li>
 *   <li>生效版本<b>不允许删除</b>（删掉会让该场景静默回落到内置提示词，是最难查的一类问题）。</li>
 * </ul>
 */
public interface AdminAiPromptService {

    /** 4.11 列表：某场景的全部版本（含草稿），并带回场景元信息。 */
    AdminAiPromptDtos.PromptList list(String sceneCode, Integer status);

    /** 场景卡片（场景编码、内置版本、当前生效版本与来源、是否由库表生效）。 */
    List<AdminAiPromptDtos.SceneOption> sceneOptions();

    /** 4.12 新增：默认草稿（除非显式 status=1）。 */
    AdminAiPromptDtos.SaveResult create(AdminAiPromptDtos.SaveRequest request);

    /** 4.12 编辑：生效版本 → 追加新版本；草稿 → 原地改（带乐观锁）。 */
    AdminAiPromptDtos.SaveResult update(Long id, AdminAiPromptDtos.SaveRequest request);

    /** 删除草稿（逻辑删除；生效版本拒绝删除）。 */
    void deleteDraft(Long id, Long configVersion);

    /** 4.13 启用某版本（同场景其它版本自动停用），并让本实例立即生效。 */
    void activate(Long id);
}
