package com.threadfit.glucopilot.user.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.user.dto.FeedbackDtos;
import com.threadfit.glucopilot.user.entity.Feedback;
import com.threadfit.glucopilot.user.feedback.support.FeedbackReplyState;
import com.threadfit.glucopilot.user.mapper.FeedbackMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 用户反馈留言中心：提交与「我的反馈」查询。 官方回复只读展示，写入属于运营端。 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private static final int RECEIVED = 0;

    private final FeedbackMapper feedbackMapper;
    private final FeedbackTypeService feedbackTypeService;

    @Transactional
    public FeedbackDtos.SubmitResult submit(Long userId, FeedbackDtos.SubmitRequest request) {
        var type = feedbackTypeService.requireEnabled(request.getFeedbackType());
        String description =
                request.getDeviceDescription() == null
                        ? null
                        : request.getDeviceDescription().trim();
        if (description != null && description.isEmpty()) description = null;
        if (description != null && description.length() > 120) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备说明最多120字");
        }
        if (!Boolean.TRUE.equals(type.getDeviceRelated())
                && (request.getDeviceId() != null || description != null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请通过设备支持类型提交设备信息");
        }
        if (request.getDeviceId() != null) {
            // 行锁与设备解绑/绑定互斥，提交时确认设备仍归本人。
            UserDevice device = feedbackMapper.lockOwnedDevice(userId, request.getDeviceId());
            if (device == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备不存在或已解绑，请重新选择本人设备");
            }
            if (description == null)
                description = device.getDeviceName() + "（" + device.getProvider() + "）";
            if (description.length() > 120) description = description.substring(0, 120);
        }
        if (Boolean.TRUE.equals(type.getDeviceRelated()) && description == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择设备或填写设备品牌型号");
        }

        Feedback row = new Feedback();
        row.setUserId(userId);
        row.setFeedbackType(request.getFeedbackType());
        row.setDeviceId(request.getDeviceId());
        row.setDeviceDescription(description);
        row.setContentText(request.getContentText().trim());
        row.setReplyStatus(RECEIVED);
        feedbackMapper.insert(row);

        return FeedbackDtos.SubmitResult.builder()
                .id(String.valueOf(row.getId()))
                .replyStatus(row.getReplyStatus())
                .build();
    }

    public PageResult<FeedbackDtos.FeedbackItem> page(Long userId, PageQuery query) {
        Page<Feedback> page =
                feedbackMapper.selectPage(
                        new Page<>(query.getPageNum(), query.getPageSize()),
                        new LambdaQueryWrapper<Feedback>()
                                .eq(Feedback::getUserId, userId)
                                .orderByDesc(Feedback::getGmtCreate)
                                .orderByDesc(Feedback::getId));
        Page<FeedbackDtos.FeedbackItem> mapped =
                new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        Map<Integer, String> names = feedbackTypeService.names();
        mapped.setRecords(page.getRecords().stream().map(f -> toItem(f, names)).toList());
        return PageResult.of(mapped);
    }

    /** 账号注销：物理删除该用户提交的反馈（正文可能含健康信息）。 */
    @Transactional
    public void purgeAccountData(Long userId) {
        feedbackMapper.purgeByUserId(userId);
    }

    private static FeedbackDtos.FeedbackItem toItem(Feedback f, Map<Integer, String> names) {
        return FeedbackDtos.FeedbackItem.builder()
                .id(String.valueOf(f.getId()))
                .feedbackType(f.getFeedbackType())
                .feedbackTypeName(names.get(f.getFeedbackType()))
                .deviceId(f.getDeviceId() == null ? null : String.valueOf(f.getDeviceId()))
                .deviceDescription(f.getDeviceDescription())
                .contentText(f.getContentText())
                .replyStatus(FeedbackReplyState.of(f))
                .officialReply(f.getOfficialReply())
                .repliedAt(f.getRepliedAt())
                .createdAt(f.getGmtCreate())
                .build();
    }
}
