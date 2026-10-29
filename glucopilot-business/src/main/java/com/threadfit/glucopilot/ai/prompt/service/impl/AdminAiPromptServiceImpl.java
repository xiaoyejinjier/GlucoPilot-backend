package com.threadfit.glucopilot.ai.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.threadfit.glucopilot.ai.prompt.dto.AdminAiPromptDtos;
import com.threadfit.glucopilot.ai.prompt.entity.AiPromptTemplateEntity;
import com.threadfit.glucopilot.ai.prompt.mapper.AiPromptTemplateMapper;
import com.threadfit.glucopilot.ai.prompt.service.AdminAiPromptService;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;

/**
 * 后台提示词管理的业务逻辑（接口 4.11~4.13）。
 *
 * <h2>版本模型：追加式 + 草稿可改</h2>
 * <ul>
 *   <li><b>新增</b>（4.12 POST）：插入一个新版本行，默认是<b>草稿</b>（{@code status=0}）。
 *       提示词直接改线上是危险的，"保存"与"启用"分离；</li>
 *   <li><b>编辑草稿</b>（4.12 PATCH，目标行 {@code status=0}）：原地更新，历史版本号不浪费；</li>
 *   <li><b>编辑已启用版本</b>（4.12 PATCH，目标行 {@code status=1}）：<b>不原地改</b>，
 *       而是复制成一个新版本（版本号 +1）并保持启用。这样"线上那一版"永远是只读的历史，
 *       出问题可以一键切回去——契约里"版本追加式，不覆盖历史版本"落在这里；</li>
 *   <li><b>启用</b>（4.13）：同场景其它版本一并停用，保证线上唯一生效版本。</li>
 * </ul>
 *
 * <h2>阻断式校验（决策 3）</h2>
 * 保存前必须全部通过，否则 400：正文非空 + 长度下限 + 变量名合法 + 按变量清单可试渲染
 * （对话场景还要含安全关键词）。<b>校验不通过的提示词不允许入库</b>——
 * 运行时的"校验不通过就退回 classpath"是第二道闸，不是第一道。
 *
 * <h2>生效</h2>
 * 任何"启用/停用"动作成功后调用 {@link AiPromptTemplateService#refreshNow()}：
 * 本实例的下一条消息就用新提示词（其它实例最多滞后 60 秒 TTL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAiPromptServiceImpl implements AdminAiPromptService {

    /** 变量名必须是合法标识符：它会被当成 ST 模板变量，非法名字在渲染期只会得到难懂的报错。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private final AiPromptTemplateMapper mapper;

    private final AiPromptTemplateService promptService;

    private final AIProperties aiProperties;

    // ------------------------------------------------------------------ 查询（4.11）

    @Override
    public AdminAiPromptDtos.PromptList list(String sceneCode, Integer status) {
        LambdaQueryWrapper<AiPromptTemplateEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(sceneCode)) {
            // 认得出就用枚举里的规范编码（库里存的是大写），认不出按原样查，查不到即空列表
            SceneCode scene = SceneCode.findByCodeOrConfigKey(sceneCode);
            wrapper.eq(AiPromptTemplateEntity::getSceneCode, scene == null ? sceneCode.trim() : scene.getCode());
        }
        if (status != null) {
            wrapper.eq(AiPromptTemplateEntity::getIsEnabled, status == 1 ? 1 : 0);
        }
        wrapper.orderByAsc(AiPromptTemplateEntity::getSceneCode)
                .orderByDesc(AiPromptTemplateEntity::getVersionNo);

        List<AdminAiPromptDtos.PromptRow> records = new ArrayList<>();
        for (AiPromptTemplateEntity entity : mapper.selectList(wrapper)) {
            records.add(toRow(entity));
        }
        return AdminAiPromptDtos.PromptList.builder()
                .records(records)
                .scenes(sceneOptions())
                .build();
    }

    /** 四个场景的元信息 + 当前生效版本（前端用它渲染场景卡片与"生效中"标记）。 */
    @Override
    public List<AdminAiPromptDtos.SceneOption> sceneOptions() {
        List<AdminAiPromptDtos.SceneOption> options = new ArrayList<>();
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = aiProperties.scene(scene);
            String source = promptService.source(scene);
            options.add(AdminAiPromptDtos.SceneOption.builder()
                    .sceneCode(scene.getCode())
                    .configKey(scene.getConfigKey())
                    .sceneName(scene.getDisplayName())
                    .requiresVision(scene.isRequiresVision())
                    .builtinVersion("v" + config.getVersion())
                    .activeVersion(promptService.versionTag(scene))
                    .activeSource(source)
                    .hasEnabledVersion(source.startsWith("db#"))
                    .variables(config.getVariables())
                    .build());
        }
        return options;
    }

    // ------------------------------------------------------------------ 新增/编辑（4.12）

    @Transactional
    @Override
    public AdminAiPromptDtos.SaveResult create(AdminAiPromptDtos.SaveRequest request) {
        SceneCode scene = requireScene(request.getSceneCode());
        Payload payload = validate(scene, request);
        int versionNo = request.getVersion() != null
                ? parseVersion(request.getVersion())
                : mapper.selectMaxVersionNo(scene.getCode()) + 1;
        requireVersionFree(scene, versionNo);

        // 新增默认草稿：提示词直接上线是危险的，"保存"与"启用"刻意分开
        boolean enable = request.getStatus() != null && request.getStatus() == 1;
        AiPromptTemplateEntity entity = new AiPromptTemplateEntity();
        entity.setSceneCode(scene.getCode());
        entity.setTemplateName(templateName(request, scene, versionNo));
        entity.setPromptBody(payload.promptText());
        entity.setVariablesJson(payload.variablesJson());
        entity.setVersionNo(versionNo);
        entity.setIsEnabled(enable ? 1 : 0);
        entity.setChangeNote(request.getChangeNote());
        entity.setConfigVersion(1L);
        mapper.insert(entity);

        if (enable) {
            mapper.deactivateOthers(scene.getCode(), entity.getId());
            promptService.refreshNow();
        } else {
            log.info("后台新增提示词草稿 scene={} version=v{} id={}", scene.getCode(), versionNo, entity.getId());
        }
        log.info("后台新增提示词 scene={} version=v{} id={} 启用={} 变量={} 改动说明={}",
                scene.getCode(), versionNo, entity.getId(), enable, payload.variables(), request.getChangeNote());
        return result(entity.getId(), versionNo, enable, false);
    }

    @Transactional
    @Override
    public AdminAiPromptDtos.SaveResult update(Long id, AdminAiPromptDtos.SaveRequest request) {
        AiPromptTemplateEntity base = require(id);
        SceneCode scene = requireScene(StringUtils.hasText(request.getSceneCode())
                ? request.getSceneCode() : base.getSceneCode());
        if (!scene.getCode().equals(base.getSceneCode())) {
            // 改场景会让这一版的历史记录（chat_session.system_prompt_ver）失去意义
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "提示词所属场景不可修改（当前 " + base.getSceneCode() + "）：请为目标场景新建版本");
        }
        Payload payload = validate(scene, request);
        boolean enable = request.getStatus() != null ? request.getStatus() == 1 : base.getIsEnabled() != null
                && base.getIsEnabled() == 1;

        // 线上生效的版本不原地改：追加新版本，旧版本留在列表里用于回滚
        if (base.getIsEnabled() != null && base.getIsEnabled() == 1) {
            // ⚠️ 请求里的 version 若与**基线版本号相同**，语义上等于"没指定新版本号"：
            // 编辑器打开时表单里就是被编辑那一版的号（v1），把它当成"新版本要叫 v1"会直接撞唯一键，
            // 表现为"保存后什么都没发生"（实测 409「场景 CHAT 已存在版本 v1」）。这里按"自动分配下一个"处理。
            //
            // ⚠️ 比较必须用 equals 而不是 `==`：这里两端都是 Integer，`==` 比的是引用，
            // 只在 -128..127 的 Integer 缓存区间里"碰巧"正确——v1 能过、v900 就 409，
            // 是本次修复第一版就被 e2e 抓住的坑（单测当时只用 v1，正好落在缓存区间里没暴露）。
            Integer requested = parseRequestedVersion(request.getVersion());
            boolean sameAsBaseline = requested != null && base.getVersionNo() != null
                    && base.getVersionNo().equals(requested);
            int versionNo = (requested == null || sameAsBaseline)
                    ? mapper.selectMaxVersionNo(scene.getCode()) + 1
                    : requested;
            requireVersionFree(scene, versionNo);

            AiPromptTemplateEntity appended = new AiPromptTemplateEntity();
            appended.setSceneCode(scene.getCode());
            appended.setTemplateName(templateName(request, scene, versionNo));
            appended.setPromptBody(payload.promptText());
            appended.setVariablesJson(payload.variablesJson());
            appended.setVersionNo(versionNo);
            appended.setIsEnabled(enable ? 1 : 0);
            appended.setChangeNote(StringUtils.hasText(request.getChangeNote())
                    ? request.getChangeNote() : "基于 v" + base.getVersionNo() + " 修改");
            appended.setConfigVersion(1L);
            mapper.insert(appended);

            if (enable) {
                mapper.deactivateOthers(scene.getCode(), appended.getId());
                promptService.refreshNow();
            }
            log.info("后台编辑已启用提示词 → 追加新版本 scene={} 原=v{} 新=v{} 新id={} 启用={}",
                    scene.getCode(), base.getVersionNo(), versionNo, appended.getId(), enable);
            return result(appended.getId(), versionNo, enable, true);
        }

        // 草稿：原地更新（带乐观锁）
        if (request.getConfigVersion() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 configVersion（乐观锁），请刷新后重试");
        }
        // 草稿允许改版本号（编辑器里那一栏是可编辑的）：只要求目标号在本场景内没被占用
        Integer requested = parseRequestedVersion(request.getVersion());
        int versionNo = base.getVersionNo();
        if (requested != null && !requested.equals(base.getVersionNo())) {
            requireVersionFreeExcept(scene, requested, id);
            versionNo = requested;
        }
        AiPromptTemplateEntity entity = new AiPromptTemplateEntity();
        entity.setId(id);
        entity.setTemplateName(templateName(request, scene, versionNo));
        entity.setPromptBody(payload.promptText());
        entity.setVariablesJson(payload.variablesJson());
        entity.setVersionNo(versionNo);
        entity.setIsEnabled(enable ? 1 : 0);
        entity.setChangeNote(request.getChangeNote());
        if (mapper.updateWithVersion(entity, request.getConfigVersion()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被他人修改，请刷新后重试");
        }
        if (enable) {
            mapper.deactivateOthers(scene.getCode(), id);
            promptService.refreshNow();
        }
        log.info("后台编辑提示词草稿 id={} scene={} version=v{}（原 v{}）启用={}",
                id, scene.getCode(), versionNo, base.getVersionNo(), enable);
        return result(id, versionNo, enable, false);
    }

    // ------------------------------------------------------------------ 删除草稿

    /**
     * 删除一个<b>草稿</b>版本（逻辑删除）。
     *
     * <h2>为什么只允许删草稿</h2>
     * 生效中的那一版是这个场景唯一的提示词来源：删掉它，运行时会静默回落到 classpath 内置提示词
     * （行为还在、但后台看不到"现在用的是哪一份"），是最难查的一类问题。
     * 想换掉生效版本的正确动作是<b>启用另一版</b>（旧版自动停用），而不是删除。
     *
     * <h2>并发</h2>
     * "只删草稿"写进了 SQL 的 WHERE（见 {@link AiPromptTemplateMapper#logicalDeleteDraft}），
     * 因此与"启用该版本"并发时，删除会因为已经不是草稿而失败（409），不会误删线上版本。
     *
     * @param configVersion 可选乐观锁：给了就校验（前端会把列表里的值带上），没给只做状态校验
     */
    @Transactional
    @Override
    public void deleteDraft(Long id, Long configVersion) {
        AiPromptTemplateEntity row = require(id);
        if (row.getIsEnabled() != null && row.getIsEnabled() == 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "「" + ("v" + row.getVersionNo()) + "」正在生效，不能删除：请先启用其它版本，再删这一版");
        }
        long expected = configVersion != null
                ? configVersion
                : (row.getConfigVersion() == null ? 0L : row.getConfigVersion());
        Long opsId = RequestContext.getOpsId();
        if (mapper.logicalDeleteDraft(id, opsId, expected) == 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该版本已被他人修改或已生效，请刷新列表后重试");
        }
        log.info("后台删除提示词草稿 id={} scene={} version=v{} 操作人={}",
                id, row.getSceneCode(), row.getVersionNo(), opsId);
    }

    // ------------------------------------------------------------------ 启用（4.13）

    @Transactional
    @Override
    public void activate(Long id) {
        AiPromptTemplateEntity row = require(id);
        SceneCode scene = SceneCode.findByCode(row.getSceneCode());
        if (scene == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该版本的场景编码已无法识别：" + row.getSceneCode());
        }
        // 启用前再校验一次：绕过接口直接改库写坏的版本不能被启用
        validateBody(scene, row.getPromptBody(), parseVariables(row.getVariablesJson(), scene));
        if (row.getIsEnabled() != null && row.getIsEnabled() == 1) {
            return;
        }
        mapper.deactivateOthers(row.getSceneCode(), id);
        if (mapper.updateEnabled(id, 1, row.getConfigVersion()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被他人修改，请刷新后重试");
        }
        promptService.refreshNow();
        log.info("后台启用提示词 scene={} version=v{} id={} 生效标签={}",
                scene.getCode(), row.getVersionNo(), id, promptService.versionTag(scene));
    }

    // ------------------------------------------------------------------ 校验

    /** 阻断式校验的结果：正文与变量清单（库里存的 variables_json 由变量清单生成）。 */
    private record Payload(String promptText, List<String> variables, String variablesJson) {
    }

    private Payload validate(SceneCode scene, AdminAiPromptDtos.SaveRequest request) {
        List<String> variables = request.getVariables() != null
                ? normalizeVariables(request.getVariables(), scene)
                : parseVariables(request.getVariablesJson(), scene);
        validateBody(scene, request.getPromptText(), variables);
        return new Payload(request.getPromptText(), variables, toVariablesJson(variables));
    }

    /** 正文的三道校验：长度 → 变量可渲染 → 安全关键词。任一条不过都抛 400。 */
    private void validateBody(SceneCode scene, String promptText, List<String> variables) {
        if (!StringUtils.hasText(promptText)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词正文不能为空");
        }
        int minLength = aiProperties.getPrompt().getMinLength();
        if (promptText.trim().length() < minLength) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "提示词正文过短（" + promptText.trim().length() + " 字，要求至少 " + minLength
                            + " 字）：过短的提示词几乎一定是误清空或误粘贴");
        }
        try {
            AiPromptTemplateService.validateRenderable(promptText, variables,
                    "scene=" + scene.getCode());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        requireSafetyKeywords(scene, promptText);
    }

    /**
     * 对话场景必须留专业就医指引。
     * <p>
     * 提示词里的合规约束是软约束（模型可能不遵守），但"连一句就医指引都没有"是硬性的内容缺失，
     * 因此放在保存前拦截，而不是等上线后靠人发现。其它场景是内部提取/识别提示词，不在本规则内。
     */
    private void requireSafetyKeywords(SceneCode scene, String promptText) {
        AIProperties.PromptProperties config = aiProperties.getPrompt();
        if (config.getSafetyScenes() == null || !config.getSafetyScenes().contains(scene.getConfigKey())) {
            return;
        }
        List<String> keywords = config.getSafetyKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        boolean hit = keywords.stream().anyMatch(promptText::contains);
        if (!hit) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "场景 " + scene.getCode() + " 的提示词必须包含专业就医相关的关键词之一（"
                            + String.join("、", keywords) + "），请补上免责/就医指引后再保存");
        }
    }

    private List<String> normalizeVariables(List<String> variables, SceneCode scene) {
        List<String> normalized = new ArrayList<>();
        for (String variable : variables) {
            if (!StringUtils.hasText(variable)) {
                continue;
            }
            String name = variable.trim();
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "变量名不合法：" + name + "（只能是字母/数字/下划线，且不以数字开头）");
            }
            normalized.add(name);
        }
        log.debug("场景 {} 的变量清单：{}", scene.getCode(), normalized);
        return normalized;
    }

    private static String toVariablesJson(List<String> variables) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(variables.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** 宽容解析 {@code variables_json}：只认双引号里的名字。 */
    static List<String> parseVariables(String variablesJson, SceneCode scene) {
        List<String> names = new ArrayList<>();
        if (!StringUtils.hasText(variablesJson)) {
            return names;
        }
        java.util.regex.Matcher matcher = Pattern.compile("\"([^\"\\\\]+)\"").matcher(variablesJson);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 解析请求里的版本号；<b>空白视为"未指定"（返回 null）而不是报错</b>——
     * 前端把"留空"提交为 null 或空串都应当被同一套语义接住。
     */
    private static Integer parseRequestedVersion(String version) {
        return StringUtils.hasText(version) ? parseVersion(version) : null;
    }

    private static int parseVersion(String version) {
        String raw = version.trim();
        if (raw.startsWith("v") || raw.startsWith("V")) {
            raw = raw.substring(1);
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                throw new NumberFormatException("必须为正整数");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版本号不合法：" + version + "（示例：v2 或 2）");
        }
    }

    private void requireVersionFree(SceneCode scene, int versionNo) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<AiPromptTemplateEntity>()
                .eq(AiPromptTemplateEntity::getSceneCode, scene.getCode())
                .eq(AiPromptTemplateEntity::getVersionNo, versionNo));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "场景 " + scene.getCode() + " 已存在版本 v" + versionNo + "，请换一个版本号（或直接编辑该版本）");
        }
    }

    /** 草稿改版本号时用：排除自己，避免"改成原来的号"被判成冲突。 */
    private void requireVersionFreeExcept(SceneCode scene, int versionNo, Long selfId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<AiPromptTemplateEntity>()
                .eq(AiPromptTemplateEntity::getSceneCode, scene.getCode())
                .eq(AiPromptTemplateEntity::getVersionNo, versionNo)
                .ne(AiPromptTemplateEntity::getId, selfId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "场景 " + scene.getCode() + " 已存在版本 v" + versionNo + "，草稿不能改成该版本号");
        }
    }

    private static SceneCode requireScene(String sceneCode) {
        SceneCode scene = SceneCode.findByCodeOrConfigKey(sceneCode);
        if (scene == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知的场景编码：" + sceneCode
                    + "（可选：" + java.util.Arrays.toString(SceneCode.values()) + "）");
        }
        return scene;
    }

    private AiPromptTemplateEntity require(Long id) {
        AiPromptTemplateEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "提示词版本不存在：" + id);
        }
        return entity;
    }

    private static String templateName(AdminAiPromptDtos.SaveRequest request, SceneCode scene, int versionNo) {
        if (StringUtils.hasText(request.getTemplateName())) {
            return request.getTemplateName().trim();
        }
        return scene.getDisplayName() + " v" + versionNo;
    }

    private static AdminAiPromptDtos.SaveResult result(Long id, int versionNo, boolean enabled, boolean appended) {
        return AdminAiPromptDtos.SaveResult.builder()
                .id(id)
                .version("v" + versionNo)
                .status(enabled ? 1 : 0)
                .appended(appended)
                .build();
    }

    private static AdminAiPromptDtos.PromptRow toRow(AiPromptTemplateEntity entity) {
        SceneCode scene = SceneCode.findByCode(entity.getSceneCode());
        List<String> variables = parseVariables(entity.getVariablesJson(), scene);
        return AdminAiPromptDtos.PromptRow.builder()
                .id(entity.getId())
                .sceneCode(entity.getSceneCode())
                .sceneName(entity.getSceneCode() == null ? null
                        : (scene == null ? entity.getSceneCode() : scene.getDisplayName()))
                .templateName(entity.getTemplateName())
                .version("v" + entity.getVersionNo())
                .versionNo(entity.getVersionNo())
                .promptText(entity.getPromptBody())
                .variables(variables)
                .variablesJson(entity.getVariablesJson())
                .status(entity.getIsEnabled() != null && entity.getIsEnabled() == 1 ? 1 : 0)
                .active(entity.getIsEnabled() != null && entity.getIsEnabled() == 1)
                .changeNote(entity.getChangeNote())
                .configVersion(entity.getConfigVersion())
                .gmtModified(entity.getGmtModified())
                .build();
    }
}
