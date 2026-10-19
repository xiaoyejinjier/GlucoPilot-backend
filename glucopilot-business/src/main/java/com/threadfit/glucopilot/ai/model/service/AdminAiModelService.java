package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.model.dto.AdminAiModelDtos;

/**
 * 后台模型配置契约（P0/P1 模型后台化：多模型、密钥加密落库、默认模型、连通性测试）。
 *
 * <h2>密钥的两条纪律</h2>
 * <ul>
 *   <li><b>加密落库</b>：{@code api_key} 以密文存储，列表/详情只回 {@code hasKey} 与掩码，
 *       任何接口都不返回明文；</li>
 *   <li><b>测试用"本行自己的凭据"</b>：连通性测试必须用被测那一行的 key/endpoint，
 *       而不是"当前生效模型"的——否则测通了也说明不了这一行配得对。</li>
 * </ul>
 *
 * <h2>为什么每次写操作后都要 refresh</h2>
 * 运行时是快照读取（见 {@link AiRuntimeConfigService}）：写完不刷新，运营会看到"改了没生效"。
 * 因此 create/update/setEnabled/setDefault 都必须在同一次调用里触发本实例刷新。
 */
public interface AdminAiModelService {

    /** 列表（不含明文密钥）。 */
    AdminAiModelDtos.ModelList list();

    /** 详情（同样不含明文密钥）。 */
    AdminAiModelDtos.ModelRow detail(Long id);

    /** 新增一行模型配置（默认不启用、不设为默认）。 */
    AdminAiModelDtos.ModelRow create(AdminAiModelDtos.SaveRequest request);

    /** 更新一行（乐观锁）；空 apiKey 表示"不改密钥"。 */
    AdminAiModelDtos.ModelRow update(Long id, AdminAiModelDtos.SaveRequest request);

    /** 启用/停用。 */
    AdminAiModelDtos.ModelRow setEnabled(Long id, Integer enabled);

    /** 设为文本通道默认模型（同 provider 内唯一）。 */
    AdminAiModelDtos.ModelRow setDefault(Long id);

    /** 连通性测试：用这一行自己的凭据发一次最小请求。 */
    AdminAiModelDtos.TestResult test(Long id);
}
