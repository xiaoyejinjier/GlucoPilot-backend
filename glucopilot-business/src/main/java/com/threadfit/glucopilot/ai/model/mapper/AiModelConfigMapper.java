package com.threadfit.glucopilot.ai.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.model.entity.AiModelConfigEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 模型配置读写。
 * <p>
 * 只额外需要两条自定义语句：一条"取启用配置"给运行时加载用（绕开逻辑删除列以外的干扰），
 * 一条"带版本号的更新"给后台保存用（乐观锁：并发编辑时后保存者失败而不是互相覆盖）。
 */
public interface AiModelConfigMapper extends BaseMapper<AiModelConfigEntity> {

    /** 运行时加载：所有启用且未删除的配置，按默认优先、编码排序，保证结果稳定可比。 */
    @Select("SELECT * FROM ai_model_config WHERE is_deleted = 0 AND is_enabled = 1 "
            + "ORDER BY is_default DESC, model_code ASC")
    List<AiModelConfigEntity> selectEnabled();

    /**
     * 乐观锁更新配置字段。
     * <p>
     * 不用 MyBatis-Plus 的 {@code @Version} 是因为它需要额外配置拦截器，而本项目只想要"这一条语句"的
     * 并发保护；显式写在 SQL 里，行为一眼可见：{@code config_version} 不匹配时返回 0 行，调用方据此报冲突。
     */
    @Update("UPDATE ai_model_config SET "
            + "model_name = #{e.modelName}, provider_code = #{e.providerCode}, capability = #{e.capability}, "
            + "api_base_url = #{e.apiBaseUrl}, "
            + "model_endpoint = #{e.modelEndpoint}, temperature = #{e.temperature}, top_p = #{e.topP}, "
            + "max_tokens = #{e.maxTokens}, thinking = #{e.thinking}, timeout_ms = #{e.timeoutMs}, "
            + "extra_params = #{e.extraParams}, is_enabled = #{e.isEnabled}, is_default = #{e.isDefault}, "
            + "remark = #{e.remark}, verify_skipped_reason = #{e.verifySkippedReason}, "
            + "api_key_cipher = COALESCE(#{cipher}, api_key_cipher), "
            + "cipher_algo = COALESCE(#{cipherAlgo}, cipher_algo), "
            + "key_version = COALESCE(#{keyVersion}, key_version), "
            + "config_version = config_version + 1 "
            + "WHERE id = #{e.id} AND is_deleted = 0 AND config_version = #{expectedVersion}")
    int updateWithVersion(@Param("e") AiModelConfigEntity entity,
                          @Param("expectedVersion") Long expectedVersion,
                          @Param("cipher") String cipher,
                          @Param("cipherAlgo") String cipherAlgo,
                          @Param("keyVersion") Integer keyVersion);

    /** 单条切换启停 / 默认（也走版本号，避免与并发的整体保存互相踩）。 */
    @Update("UPDATE ai_model_config SET is_enabled = #{enabled}, config_version = config_version + 1 "
            + "WHERE id = #{id} AND is_deleted = 0")
    int updateEnabled(@Param("id") Long id, @Param("enabled") int enabled);

    /**
     * 显式清空密钥，回到"沿用环境变量/yaml"。
     * <p>
     * 单独一条语句而不是复用更新语句：{@link #updateWithVersion} 用 {@code COALESCE} 保留旧密文
     * （这是"留空=不改"的实现），而清空需要的是"确实置 NULL"，两者语义相反，混在一条里必然出错。
     */
    @Update("UPDATE ai_model_config SET api_key_cipher = NULL, cipher_algo = NULL, key_version = NULL, "
            + "config_version = config_version + 1 "
            + "WHERE id = #{id} AND is_deleted = 0 AND config_version = #{expectedVersion}")
    int clearApiKey(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion);

    /**
     * 清掉<b>同一个默认池</b>的其它默认标记。
     * <p>
     * 必须带池：文本与视觉各有一个"默认"，不分类地清会把另一个池的默认一起抹掉
     * （批次 2 之前就是这个行为——新增视觉模型标默认会顺手清掉文本默认，对话直接失去默认模型）。
     * <p>
     * 池 = 能力集合里是否含 {@code vision}（含即视觉池），与 {@code AiModelCapability#pool()} 同一口径。
     * 用 {@code LIKE '%vision%'} 而不是等值比较：{@code capability} 是逗号分隔集合，
     * token 只有 text/vision/tool/stream 四个小写词，不会出现"词里含 vision"的误判；
     * NULL 行（历史数据）按文本池处理。
     */
    @Update("UPDATE ai_model_config SET is_default = 0, config_version = config_version + 1 "
            + "WHERE is_deleted = 0 AND is_default = 1 AND id <> #{id} "
            + "AND (CASE WHEN capability LIKE '%vision%' THEN 'vision' ELSE 'text' END) = #{pool}")
    int clearOtherDefaults(@Param("id") Long id, @Param("pool") String pool);

    /**
     * 清掉单行的默认标记。
     * <p>
     * 用在"停用当前默认模型"之后：{@link #updateEnabled} 只改启用位，留着 {@code is_default=1}
     * 会让列表里出现"已停用但显示为默认"的行——2026-10-13 那个反馈里，管理员正是被这种
     * 自相矛盾的状态误导，以为新模型已经接手了。
     */
    @Update("UPDATE ai_model_config SET is_default = 0, config_version = config_version + 1 "
            + "WHERE id = #{id} AND is_deleted = 0 AND is_default = 1")
    int clearDefault(@Param("id") Long id);

    @Update("UPDATE ai_model_config SET is_default = 1, is_enabled = 1, config_version = config_version + 1 "
            + "WHERE id = #{id} AND is_deleted = 0")
    int markDefault(@Param("id") Long id);

    /** 连通性测试结果回写（不进审计、不参与版本号，避免"测试"把配置版本推高导致误判变更）。 */
    @Update("UPDATE ai_model_config SET last_test_at = NOW(3), last_test_ok = #{ok}, last_test_message = #{message} "
            + "WHERE id = #{id} AND is_deleted = 0")
    int updateTestResult(@Param("id") Long id, @Param("ok") boolean ok, @Param("message") String message);

    /** 运行时时序：被任何启用配置引用过的最大 config_version，用于缓存失效判据。 */
    @Select("SELECT COALESCE(MAX(config_version), 0) FROM ai_model_config WHERE is_deleted = 0")
    Long selectMaxConfigVersion();
}
