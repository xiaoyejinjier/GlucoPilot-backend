package com.threadfit.glucopilot.user.feedback.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.FeedbackDtos;
import com.threadfit.glucopilot.user.mapper.FeedbackTypeMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 类型以数据库字典为准，列表一次批量读取，避免每条反馈各查一次。 */
@Service
@RequiredArgsConstructor
public class FeedbackTypeService {
    private final FeedbackTypeMapper mapper;

    public List<FeedbackDtos.TypeItem> enabledTypes() {
        return mapper.selectTypes(true);
    }

    public FeedbackDtos.TypeItem requireEnabled(Integer id) {
        return enabledTypes().stream()
                .filter(type -> type.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "反馈类型不存在或已停用"));
    }

    public Map<Integer, String> names() {
        // 停用类型仍需在历史反馈中显示原名称。
        return mapper.selectTypes(false).stream()
                .collect(
                        Collectors.toMap(
                                FeedbackDtos.TypeItem::getId, FeedbackDtos.TypeItem::getName));
    }
}
