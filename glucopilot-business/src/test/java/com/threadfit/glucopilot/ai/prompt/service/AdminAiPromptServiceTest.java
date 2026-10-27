package com.threadfit.glucopilot.ai.prompt.service;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.prompt.dto.AdminAiPromptDtos;
import com.threadfit.glucopilot.ai.prompt.entity.AiPromptTemplateEntity;
import com.threadfit.glucopilot.ai.prompt.mapper.AiPromptTemplateMapper;
import com.threadfit.glucopilot.ai.prompt.service.AdminAiPromptService;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.prompt.service.impl.AiPromptTemplateServiceImpl;
import com.threadfit.glucopilot.ai.prompt.service.impl.AdminAiPromptServiceImpl;

/**
 * 后台提示词管理的语义（接口 4.11~4.13）。
 * <p>
 * 这里守的是"改坏了能不能拦住"和"线上那一版能不能回滚"这两件事：
 * <ol>
 *   <li>阻断式校验：正文过短/缺就医指引/变量非法/花括号未声明 → 一律 400，坏提示词不允许入库；</li>
 *   <li>版本追加式：<b>编辑已启用版本不原地改</b>，而是追加新版本并保持启用，旧版本留着回滚；</li>
 *   <li>同场景唯一启用：新增启用/启用某版本时，其它版本必须被停用；</li>
 *   <li>草稿才有乐观锁：没有 configVersion 不能改草稿（否则两个管理员互相覆盖）。</li>
 * </ol>
 */
class AdminAiPromptServiceTest {

    /** 合法的对话提示词：≥50 字、含"就医"、无花括号。 */
    private static final String VALID_CHAT_PROMPT =
            "你是控糖助手，用简洁中文回答用户关于血糖的问题，不做诊断、不给用药建议，"
                    + "涉及治疗与调整用药时请引导用户及时就医。";

    /** 合法但**不含任何就医关键词**的正文（用来验证安全关键词这一条）。 */
    private static final String NO_SAFETY_PROMPT =
            "你是控糖助手，用简洁中文回答用户关于血糖的问题，只做科普解释与生活方式建议，"
                    + "保持语气平实友好，不要追问隐私信息。";

    private AiPromptTemplateMapper mapper;

    private AiPromptTemplateServiceImpl promptService;

    private AdminAiPromptService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiPromptTemplateMapper.class);
        AIProperties properties = new AIProperties();
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = new AIProperties.SceneProperties();
            config.setPrompt("prompts/chat/system.md");
            properties.getScenes().put(scene.getConfigKey(), config);
        }
        // 不连库的提示词服务：本测试只关心后台保存语义，运行时来源由 AiPromptTemplateServiceTest 覆盖
        promptService = new AiPromptTemplateServiceImpl(properties, null);
        promptService.init();
        service = new AdminAiPromptServiceImpl(mapper, promptService, properties);
    }

    private static AdminAiPromptDtos.SaveRequest request(String promptText, Integer status) {
        AdminAiPromptDtos.SaveRequest request = new AdminAiPromptDtos.SaveRequest();
        request.setSceneCode("CHAT");
        request.setPromptText(promptText);
        request.setStatus(status);
        return request;
    }

    private static AiPromptTemplateEntity entity(Long id, String sceneCode, int versionNo, int enabled,
                                                 String body, String variablesJson) {
        AiPromptTemplateEntity entity = new AiPromptTemplateEntity();
        entity.setId(id);
        entity.setSceneCode(sceneCode);
        entity.setVersionNo(versionNo);
        entity.setIsEnabled(enabled);
        entity.setPromptBody(body);
        entity.setVariablesJson(variablesJson);
        entity.setTemplateName("模板 v" + versionNo);
        entity.setConfigVersion(1L);
        return entity;
    }

    // ---------------------------------------------------------------- 阻断式校验

    @Test
    void 正文过短不允许入库() {
        BizException error = assertThrows(BizException.class,
                () -> service.create(request("太短了", 0)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("过短"));
        verify(mapper, never()).insert(any(AiPromptTemplateEntity.class));
    }

    @Test
    void 对话场景缺就医指引不允许入库() {
        BizException error = assertThrows(BizException.class,
                () -> service.create(request(NO_SAFETY_PROMPT, 0)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("就医"));
        verify(mapper, never()).insert(any(AiPromptTemplateEntity.class));
    }

    @Test
    void 模板里有未声明花括号不允许入库() {
        String body = VALID_CHAT_PROMPT + " 输出示例：{\"level\":\"ok\"}";

        BizException error = assertThrows(BizException.class, () -> service.create(request(body, 0)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("校验失败"));
        verify(mapper, never()).insert(any(AiPromptTemplateEntity.class));
    }

    @Test
    void 变量名非法不允许入库() {
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setVariables(List.of("1bad-name"));

        BizException error = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("变量名不合法"));
    }

    @Test
    void 未启用提示词的场景时不校验就医关键词() {
        when(mapper.selectMaxVersionNo("OCR_GLUCOSE")).thenReturn(0);
        AdminAiPromptDtos.SaveRequest request = request(NO_SAFETY_PROMPT, 0);
        request.setSceneCode("OCR_GLUCOSE");

        AdminAiPromptDtos.SaveResult result = service.create(request);

        assertEquals("v1", result.getVersion());
        verify(mapper).insert(any(AiPromptTemplateEntity.class));
    }

    // ---------------------------------------------------------------- 新增

    @Test
    void 新增默认草稿且不动线上版本() {
        when(mapper.selectMaxVersionNo("CHAT")).thenReturn(2);

        AdminAiPromptDtos.SaveResult result = service.create(request(VALID_CHAT_PROMPT, null));

        assertEquals("v3", result.getVersion());
        assertFalse(result.getAppended());
        assertEquals(0, result.getStatus(), "不传 status 时新增必须是草稿");
        ArgumentCaptor<AiPromptTemplateEntity> captor = ArgumentCaptor.forClass(AiPromptTemplateEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getIsEnabled());
        assertEquals("CHAT", captor.getValue().getSceneCode());
        assertEquals(3, captor.getValue().getVersionNo());
        assertEquals("[]", captor.getValue().getVariablesJson());
        verify(mapper, never()).deactivateOthers(anyString(), anyLong());
    }

    @Test
    void 新增时直接启用会停用同场景其它版本() {
        when(mapper.selectMaxVersionNo("CHAT")).thenReturn(1);

        AdminAiPromptDtos.SaveResult result = service.create(request(VALID_CHAT_PROMPT, 1));

        assertEquals(1, result.getStatus());
        ArgumentCaptor<AiPromptTemplateEntity> captor = ArgumentCaptor.forClass(AiPromptTemplateEntity.class);
        verify(mapper).insert(captor.capture());
        verify(mapper).deactivateOthers("CHAT", captor.getValue().getId());
    }

    @Test
    void 版本号重复返回409而不是覆盖已有版本() {
        when(mapper.selectMaxVersionNo("CHAT")).thenReturn(3);
        when(mapper.selectCount(any())).thenReturn(1L);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setVersion("v3");

        BizException error = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertTrue(error.getMessage().contains("已存在版本 v3"));
        verify(mapper, never()).insert(any(AiPromptTemplateEntity.class));
    }

    @Test
    void 未知场景编码返回400() {
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setSceneCode("CHAT_FUTURE");

        BizException error = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("未知的场景编码"));
    }

    // ---------------------------------------------------------------- 编辑

    /**
     * 回归：编辑器打开生效版本时，表单里的版本号就是被编辑那一版的号（v1），
     * 前端会原样回传。若把它当成"新版本要叫 v1"，就会撞唯一键报 409，
     * 用户看到的现象是"保存后什么都没发生"。这里钉住"同号 = 未指定 = 自动分配下一个"。
     */
    @Test
    void 编辑生效版本时回传自身版本号应自动追加下一个版本() {
        when(mapper.selectById(7L)).thenReturn(entity(7L, "CHAT", 1, 1, VALID_CHAT_PROMPT, "[]"));
        when(mapper.selectMaxVersionNo("CHAT")).thenReturn(1);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 1);
        request.setVersion("v1");

        AdminAiPromptDtos.SaveResult result = service.update(7L, request);

        assertTrue(result.getAppended());
        assertEquals("v2", result.getVersion(), "回传自身版本号时必须自动分配下一个，而不是报 409");
        ArgumentCaptor<AiPromptTemplateEntity> captor = ArgumentCaptor.forClass(AiPromptTemplateEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getVersionNo());
    }

    /**
     * 同一场景的**高位**版本号（不在 Integer 缓存区间内）。
     * <p>
     * 这条是被 e2e 抓出来的：第一版修复写成 `requested == base.getVersionNo()`，
     * 比的是 Integer 引用，只有 -128..127 的缓存区间才"碰巧"相等 —— v1 过、v900 报 409。
     * 单测当时只用 v1，正好落在缓存里，于是必须补一条高位版本号的用例把它钉死。
     */
    @Test
    void 编辑生效版本时回传自身的高位版本号同样自动追加() {
        when(mapper.selectById(30L)).thenReturn(entity(30L, "CHAT", 900, 1, VALID_CHAT_PROMPT, "[]"));
        when(mapper.selectMaxVersionNo("CHAT")).thenReturn(900);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 1);
        request.setVersion("v900");

        AdminAiPromptDtos.SaveResult result = service.update(30L, request);

        assertTrue(result.getAppended());
        assertEquals("v901", result.getVersion(), "高位版本号不能用 == 比较（Integer 缓存区间之外引用不等）");
    }

    @Test
    void 编辑生效版本时显式指定空闲版本号会采用它() {
        when(mapper.selectById(7L)).thenReturn(entity(7L, "CHAT", 1, 1, VALID_CHAT_PROMPT, "[]"));
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 1);
        request.setVersion("v5");

        AdminAiPromptDtos.SaveResult result = service.update(7L, request);

        assertEquals("v5", result.getVersion());
    }

    @Test
    void 编辑生效版本时撞上已占用的其它版本号仍然409() {
        when(mapper.selectById(7L)).thenReturn(entity(7L, "CHAT", 1, 1, VALID_CHAT_PROMPT, "[]"));
        when(mapper.selectCount(any())).thenReturn(1L);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 1);
        request.setVersion("v2");

        BizException error = assertThrows(BizException.class, () -> service.update(7L, request));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertTrue(error.getMessage().contains("已存在版本 v2"));
    }

    @Test
    void 草稿可以改版本号() {
        when(mapper.selectById(9L)).thenReturn(entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.updateWithVersion(any(), eq(1L))).thenReturn(1);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setVersion("v4");
        request.setConfigVersion(1L);

        AdminAiPromptDtos.SaveResult result = service.update(9L, request);

        assertEquals("v4", result.getVersion());
        ArgumentCaptor<AiPromptTemplateEntity> captor = ArgumentCaptor.forClass(AiPromptTemplateEntity.class);
        verify(mapper).updateWithVersion(captor.capture(), eq(1L));
        assertEquals(4, captor.getValue().getVersionNo());
    }

    @Test
    void 草稿改成已占用的版本号被拒() {
        when(mapper.selectById(9L)).thenReturn(entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.selectCount(any())).thenReturn(1L);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setVersion("v7");
        request.setConfigVersion(1L);

        BizException error = assertThrows(BizException.class, () -> service.update(9L, request));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(mapper, never()).updateWithVersion(any(), anyLong());
    }

    @Test
    void 编辑已启用版本时追加新版本且旧版本保留() {
        when(mapper.selectById(7L)).thenReturn(entity(7L, "CHAT", 1, 1, VALID_CHAT_PROMPT, "[]"));
        when(mapper.selectMaxVersionNo("CHAT")).thenReturn(1);

        AdminAiPromptDtos.SaveResult result = service.update(7L, request(VALID_CHAT_PROMPT, null));

        assertTrue(result.getAppended(), "编辑已启用版本必须走追加，不能原地改线上那一版");
        assertEquals("v2", result.getVersion());
        assertEquals(1, result.getStatus(), "追加时继承原版本的启用状态");
        ArgumentCaptor<AiPromptTemplateEntity> captor = ArgumentCaptor.forClass(AiPromptTemplateEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getVersionNo());
        assertEquals(1, captor.getValue().getIsEnabled());
        verify(mapper).deactivateOthers("CHAT", captor.getValue().getId());
        // 关键：原行没有被 UPDATE
        verify(mapper, never()).updateWithVersion(any(), anyLong());
    }

    @Test
    void 编辑草稿必须带乐观锁且原地更新() {
        when(mapper.selectById(9L)).thenReturn(entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));

        BizException error = assertThrows(BizException.class, () -> service.update(9L, request(VALID_CHAT_PROMPT, 0)));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("configVersion"));

        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setConfigVersion(1L);
        when(mapper.updateWithVersion(any(), eq(1L))).thenReturn(1);

        AdminAiPromptDtos.SaveResult result = service.update(9L, request);

        assertEquals(9L, result.getId());
        assertEquals("v2", result.getVersion());
        assertFalse(result.getAppended());
        verify(mapper).updateWithVersion(any(), eq(1L));
    }

    @Test
    void 草稿乐观锁冲突返回409() {
        when(mapper.selectById(9L)).thenReturn(entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.updateWithVersion(any(), eq(1L))).thenReturn(0);
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setConfigVersion(1L);

        BizException error = assertThrows(BizException.class, () -> service.update(9L, request));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test
    void 编辑时改场景被拒绝() {
        when(mapper.selectById(7L)).thenReturn(entity(7L, "CHAT", 1, 0, VALID_CHAT_PROMPT, "[]"));
        AdminAiPromptDtos.SaveRequest request = request(VALID_CHAT_PROMPT, 0);
        request.setSceneCode("MEAL_ANALYSIS");
        request.setConfigVersion(1L);

        BizException error = assertThrows(BizException.class, () -> service.update(7L, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("场景不可修改"));
    }

    // ---------------------------------------------------------------- 启用

    @Test
    void 启用会停用同场景其它版本并写入版本条件() {
        when(mapper.selectById(8L)).thenReturn(entity(8L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.updateEnabled(eq(8L), eq(1), eq(1L))).thenReturn(1);

        service.activate(8L);

        verify(mapper).deactivateOthers("CHAT", 8L);
        verify(mapper).updateEnabled(8L, 1, 1L);
    }

    @Test
    void 启用时版本被别人改过要报冲突() {
        when(mapper.selectById(8L)).thenReturn(entity(8L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.updateEnabled(eq(8L), eq(1), eq(1L))).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> service.activate(8L));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test
    void 启用校验不通过的版本被拒绝() {
        // 绕过接口直接写库把正文写坏（花括号未声明）——启用前必须再校验一次
        when(mapper.selectById(8L)).thenReturn(
                entity(8L, "CHAT", 2, 0, VALID_CHAT_PROMPT + " {\"a\":1}", "[]"));

        BizException error = assertThrows(BizException.class, () -> service.activate(8L));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(mapper, never()).updateEnabled(anyLong(), anyInt(), anyLong());
    }

    @Test
    void 版本不存在返回404() {
        when(mapper.selectById(404L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.activate(404L));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    // ---------------------------------------------------------------- 删除草稿

    @Test
    void 可以删除草稿并带上操作人与乐观锁() {
        when(mapper.selectById(9L)).thenReturn(entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.logicalDeleteDraft(eq(9L), any(), eq(1L))).thenReturn(1);

        service.deleteDraft(9L, 1L);

        verify(mapper).logicalDeleteDraft(9L, null, 1L);
    }

    @Test
    void 删除时不给乐观锁则用行上的版本() {
        AiPromptTemplateEntity row = entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]");
        row.setConfigVersion(7L);
        when(mapper.selectById(9L)).thenReturn(row);
        when(mapper.logicalDeleteDraft(eq(9L), any(), eq(7L))).thenReturn(1);

        service.deleteDraft(9L, null);

        verify(mapper).logicalDeleteDraft(9L, null, 7L);
    }

    @Test
    void 生效版本不允许删除() {
        when(mapper.selectById(4L)).thenReturn(entity(4L, "CHAT", 1, 1, VALID_CHAT_PROMPT, "[]"));

        BizException error = assertThrows(BizException.class, () -> service.deleteDraft(4L, null));

        assertEquals(ErrorCode.BIZ_REJECTED, error.getErrorCode());
        assertTrue(error.getMessage().contains("正在生效"));
        verify(mapper, never()).logicalDeleteDraft(anyLong(), any(), anyLong());
    }

    @Test
    void 并发下版本刚被启用则删除失败返回409() {
        when(mapper.selectById(9L)).thenReturn(entity(9L, "CHAT", 2, 0, VALID_CHAT_PROMPT, "[]"));
        // 服务层读到的是草稿，但执行时 SQL 里 is_enabled = 0 已不成立（别人抢先启用了）
        when(mapper.logicalDeleteDraft(eq(9L), any(), anyLong())).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> service.deleteDraft(9L, 1L));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test
    void 删除不存在的版本返回404() {
        when(mapper.selectById(404L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.deleteDraft(404L, null));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    // ---------------------------------------------------------------- 列表

    @Test
    void 列表返回全部版本并标记生效版本与场景来源() {
        List<AiPromptTemplateEntity> rows = new ArrayList<>();
        rows.add(entity(2L, "CHAT", 2, 1, VALID_CHAT_PROMPT, "[\"foodText\"]"));
        rows.add(entity(1L, "CHAT", 1, 0, VALID_CHAT_PROMPT, "[]"));
        when(mapper.selectList(any())).thenReturn(rows);

        AdminAiPromptDtos.PromptList list = service.list("chat", null);

        assertEquals(2, list.getRecords().size());
        AdminAiPromptDtos.PromptRow active = list.getRecords().get(0);
        assertEquals("v2", active.getVersion());
        assertEquals(1, active.getStatus());
        assertTrue(active.getActive());
        assertEquals(List.of("foodText"), active.getVariables());
        // 场景枚举里现在有 7 个（② 期 SUMMARY、③ 期 MEMORY、社区 AI 审核新增 MODERATION）。
        // 这条断言的价值就在于"新增场景必须显式改这里"：后台提示词页的场景下拉是从这里来的，
        // 漏改不会报错，只会在运营页上少一个可选场景。
        assertEquals(7, list.getScenes().size());
        // 未连库 → 运行时会用 classpath 内置提示词，接口必须如实汇报来源
        AdminAiPromptDtos.SceneOption chat = list.getScenes().stream()
                .filter(option -> "CHAT".equals(option.getSceneCode())).findFirst().orElseThrow();
        assertEquals("classpath", chat.getActiveSource());
        assertFalse(chat.getHasEnabledVersion());
        assertEquals("chat@v1", chat.getActiveVersion());
        verify(mapper, times(1)).selectList(any());
    }
}
