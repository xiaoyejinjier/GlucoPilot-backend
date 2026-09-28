package com.threadfit.glucopilot.user.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.entity.SysConfig;
import com.threadfit.glucopilot.ruoyi.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C 端公开配置：只放行 GLUCOSE / TEXT / APPEARANCE / NOTIFY 四个分组，
 * PUSH 与 AI 含密钥类参数，任何情况下都不对外返回。
 */
@Service
@RequiredArgsConstructor
public class AppConfigService {

    public static final Set<String> PUBLIC_GROUPS = Set.of("GLUCOSE", "TEXT", "APPEARANCE", "NOTIFY");

    private static final int TYPE_STRING = 1;
    private static final int TYPE_NUMBER = 2;
    private static final int TYPE_BOOLEAN = 3;

    private final SysConfigMapper sysConfigMapper;

    /** 按分组返回配置：{ "GLUCOSE": { "glucose.target.low": 3.9 } }。 */
    public Map<String, Map<String, Object>> publicConfigs(List<String> requestedGroups) {
        List<String> groups = requestedGroups == null || requestedGroups.isEmpty()
                ? List.copyOf(PUBLIC_GROUPS)
                : requestedGroups.stream()
                .filter(StringUtils::hasText)
                .map(g -> g.trim().toUpperCase())
                .toList();
        for (String group : groups) {
            if (!PUBLIC_GROUPS.contains(group)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持的分组: " + group);
            }
        }

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        groups.forEach(group -> result.put(group, new LinkedHashMap<>()));
        sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                        .in(SysConfig::getConfigGroup, groups)
                        .orderByAsc(SysConfig::getId))
                .forEach(config -> result.get(config.getConfigGroup())
                        .put(config.getConfigKey(), typedValue(config)));
        return result;
    }

    /** 读取 TEXT 分组的单个字符串参数；不存在返回 null。 */
    public String textValue(String configKey) {
        SysConfig config = find(configKey);
        return config == null ? null : config.getConfigValue();
    }

    private SysConfig find(String configKey) {
        return sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, configKey)
                .last("LIMIT 1"));
    }

    /** value_type：1字符串 2数字 3布尔 4JSON；无法按声明类型解析时退回原始字符串。 */
    private static Object typedValue(SysConfig config) {
        String raw = config.getConfigValue();
        if (raw == null) {
            return null;
        }
        int type = config.getValueType() == null ? TYPE_STRING : config.getValueType();
        try {
            return switch (type) {
                case TYPE_NUMBER -> new BigDecimal(raw.trim());
                case TYPE_BOOLEAN -> Boolean.valueOf(raw.trim());
                default -> raw;
            };
        } catch (NumberFormatException ex) {
            return raw;
        }
    }
}
