package com.threadfit.glucopilot.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code ai_model_config} 的实体（V1 建表、V13 扩列）。
 * <p>
 * 注意：本项目在 {@code PersistenceConfiguration} 里给业务侧 SqlSessionFactory 配了
 * MyBatis-Plus 的逻辑删除字段与审计填充，所以这里只需声明字段，审计列由
 * {@code MetaObjectHandler} 自动写入。
 */
@Data
@TableName("ai_model_config")
public class AiModelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型编码：场景/代码通过它引用模型，一旦被引用就不应随意改名。 */
    private String modelCode;

    private String modelName;

    /** 厂商：1 自研 2 OpenAI 兼容 3 通义 4 文心 5 其他（沿用 DDL 注释）。 */
    private Integer providerCode;

    /**
     * 能力集合：逗号分隔，取值 {@code text} / {@code vision} / {@code tool} / {@code stream}。
     * <p>
     * 由 V46 引入（V45 的 {@code model_type} 二值字段升级而来，列名同时改掉）。
     * 为 null 或空串时按"只有 text"处理（历史行与旧前端都写不出这个字段）。
     * 它与 {@link #providerCode} 正交：厂商决定"用什么协议连"，能力决定"能干什么、归哪个默认池"。
     */
    private String capability;

    private String apiBaseUrl;

    /** 密钥密文（AES-GCM）。为空表示"沿用环境变量/yaml 里的密钥"。 */
    private String apiKeyCipher;

    private String cipherAlgo;

    private Integer keyVersion;

    /** 模型名 / 部署名，真正发给 provider 的那个名字。 */
    private String modelEndpoint;

    private BigDecimal temperature;

    private BigDecimal topP;

    private Integer maxTokens;

    /** 是否开启推理链（DeepSeek 特有）；null = 不显式设置，用 provider 默认。 */
    private Boolean thinking;

    private Integer timeoutMs;

    private String extraParams;

    private Integer isDefault;

    private Integer isEnabled;

    /** 配置版本：乐观锁判据 + 缓存失效判据，任何写操作都 +1。 */
    private Long configVersion;

    private LocalDateTime lastTestAt;

    private Boolean lastTestOk;

    private String lastTestMessage;

    private String remark;

    private Long createdBy;

    private LocalDateTime gmtCreate;

    private Long updatedBy;

    private LocalDateTime gmtModified;

    @TableLogic
    private Integer isDeleted;
}
