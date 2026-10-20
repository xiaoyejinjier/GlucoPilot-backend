package com.threadfit.glucopilot.ai.prompt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import com.threadfit.glucopilot.ai.prompt.service.AdminAiPromptService;

/**
 * 后台"提示词管理"（接口 4.11~4.13）的出入参。
 * <p>
 * 字段名与《接口设计文档》4.12 对齐（{@code sceneCode/templateName/version/promptText/variablesJson/status}）；
 * 出参在契约基础上多了几个<b>只读</b>字段（{@code active/changeNote/configVersion/gmtModified}），
 * 用于前端判断"哪一版在线上"以及做乐观锁，不影响契约里的字段解析。
 */
public class AdminAiPromptDtos {

    /** 4.11 出参：{@code data.records} + 场景元信息（前端据此渲染场景切换与"当前生效来源"）。 */
    @Data
    @Builder
    public static class PromptList {

        private List<PromptRow> records;

        /** 场景清单（含当前生效版本与来源），与 records 无关，恒返回四个场景。 */
        private List<SceneOption> scenes;
    }

    /** 一条提示词版本。 */
    @Data
    @Builder
    public static class PromptRow {

        private Long id;

        private String sceneCode;

        /** 场景中文名，前端直接展示。 */
        private String sceneName;

        private String templateName;

        /** 对外版本号，如 {@code v3}（库表里存的是 {@code version_no=3}）。 */
        private String version;

        private Integer versionNo;

        /** 提示词正文。 */
        private String promptText;

        /** 变量名清单（从 {@code variables_json} 解析而来，前端直接展示为标签）。 */
        private List<String> variables;

        private String variablesJson;

        /** 0 停用 1 启用（契约字段名）。 */
        private Integer status;

        /** 是否当前生效版本（同场景唯一的启用版本）。 */
        private Boolean active;

        /** 改动说明。 */
        private String changeNote;

        /** 乐观锁版本（编辑草稿时回传）。 */
        private Long configVersion;

        private LocalDateTime gmtModified;
    }

    /** 场景选项：给前端做场景切换与"现在生效的是哪一版、来自哪里"。 */
    @Data
    @Builder
    public static class SceneOption {

        private String sceneCode;

        private String configKey;

        private String sceneName;

        /** 是否需要视觉模型（前端据此提示"该场景的模型由视觉通道统一解析"）。 */
        private Boolean requiresVision;

        /** classpath 内置版本号，如 {@code v1}。 */
        private String builtinVersion;

        /** 当前生效的版本标签，如 {@code chat@v3}（落 chat_session.system_prompt_ver 的那个值）。 */
        private String activeVersion;

        /** 当前生效来源：{@code db#12} 或 {@code classpath}。 */
        private String activeSource;

        /** 库里是否有启用版本（false = 正在用 classpath 内置提示词兜底）。 */
        private Boolean hasEnabledVersion;

        /** 该场景声明的变量名（新建版本时的初始值）。 */
        private List<String> variables;
    }

    /** 4.12 入参（新增与编辑共用）。 */
    @Data
    public static class SaveRequest {

        @NotBlank(message = "场景编码不能为空")
        @Size(max = 64, message = "场景编码最长 64 位")
        private String sceneCode;

        @Size(max = 64, message = "模板名称最长 64 位")
        private String templateName;

        /** 版本号，如 {@code v2} 或 {@code 2}；留空 = 同场景下一个版本号。 */
        @Size(max = 32, message = "版本号最长 32 位")
        private String version;

        @NotBlank(message = "提示词正文不能为空")
        private String promptText;

        /** 变量定义（JSON 数组字符串）。与 {@link #variables} 同时给出时以 variables 为准。 */
        private String variablesJson;

        /** 变量名清单（推荐前端用这个字段）。 */
        private List<String> variables;

        /** 0 停用 1 启用；留空 = 新增按草稿（0）、编辑沿用原状态。 */
        @Min(value = 0, message = "status 只能是 0 或 1")
        @Max(value = 1, message = "status 只能是 0 或 1")
        private Integer status;

        @Size(max = 255, message = "改动说明最长 255 位")
        private String changeNote;

        /** 乐观锁：编辑草稿时必填（列表里回传），追加新版本时忽略。 */
        private Long configVersion;
    }

    /** 4.12 出参。 */
    @Data
    @Builder
    public static class SaveResult {

        /** 落库的模板 ID（契约字段）。 */
        private Long id;

        private String version;

        private Integer status;

        /** true = 本次是"追加新版本"（编辑已启用版本时不会原地改，见 AdminAiPromptService）。 */
        private Boolean appended;
    }
}
