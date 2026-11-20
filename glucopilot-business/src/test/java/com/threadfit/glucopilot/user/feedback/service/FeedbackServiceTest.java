package com.threadfit.glucopilot.user.feedback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.user.dto.FeedbackDtos;
import com.threadfit.glucopilot.user.entity.Feedback;
import com.threadfit.glucopilot.user.mapper.FeedbackMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private FeedbackMapper feedbackMapper;
    @Mock private FeedbackTypeService feedbackTypeService;

    @InjectMocks private FeedbackService service;

    @Test
    void rejectsUnknownFeedbackType() {
        when(feedbackTypeService.requireEnabled(9))
                .thenThrow(
                        new BizException(
                                com.threadfit.glucopilot.common.api.ErrorCode.BAD_REQUEST,
                                "反馈类型不存在或已停用"));
        assertThrows(BizException.class, () -> service.submit(USER_ID, request(9, "内容")));
    }

    @Test
    void submitStoresTrimmedContentAndReceivedStatus() {
        when(feedbackTypeService.requireEnabled(1)).thenReturn(type(1, false));
        when(feedbackMapper.insert(any(Feedback.class)))
                .thenAnswer(
                        inv -> {
                            Feedback row = inv.getArgument(0);
                            row.setId(12L);
                            return 1;
                        });

        FeedbackDtos.SubmitResult result = service.submit(USER_ID, request(1, "  希望增加 CGM 对接  "));

        assertEquals("12", result.getId());
        assertEquals(0, result.getReplyStatus());
        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertEquals("希望增加 CGM 对接", captor.getValue().getContentText());
        assertEquals(USER_ID, captor.getValue().getUserId());
    }

    @Test
    void pageExposesOfficialReplyWhenPresent() {
        Feedback replied = new Feedback();
        replied.setId(1L);
        replied.setUserId(USER_ID);
        replied.setFeedbackType(1);
        replied.setContentText("希望增加连续血糖监测设备的数据导入。");
        replied.setReplyStatus(2);
        replied.setOfficialReply("已收录，感谢您的建议。");
        replied.setRepliedAt(LocalDateTime.of(2026, 9, 16, 10, 0));
        replied.setGmtCreate(LocalDateTime.of(2026, 9, 15, 16, 28));

        when(feedbackMapper.selectPage(any(), any()))
                .thenAnswer(
                        inv -> {
                            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Feedback>
                                    page = inv.getArgument(0);
                            page.setRecords(List.of(replied));
                            page.setTotal(1);
                            return page;
                        });

        PageResult<FeedbackDtos.FeedbackItem> page = service.page(USER_ID, new PageQuery());

        assertEquals(1, page.getTotal());
        assertEquals("已收录，感谢您的建议。", page.getRecords().get(0).getOfficialReply());
        assertEquals(2, page.getRecords().get(0).getReplyStatus());
    }

    @Test
    void pageLeavesReplyNullWhenNotAnswered() {
        Feedback pending = new Feedback();
        pending.setId(2L);
        pending.setUserId(USER_ID);
        pending.setFeedbackType(3);
        pending.setContentText("能否增加家属共享查看功能？");
        pending.setReplyStatus(0);

        when(feedbackMapper.selectPage(any(), any()))
                .thenAnswer(
                        inv -> {
                            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Feedback>
                                    page = inv.getArgument(0);
                            page.setRecords(List.of(pending));
                            page.setTotal(1);
                            return page;
                        });

        PageResult<FeedbackDtos.FeedbackItem> page = service.page(USER_ID, new PageQuery());

        assertNull(page.getRecords().get(0).getOfficialReply());
    }

    private static FeedbackDtos.SubmitRequest request(int type, String content) {
        FeedbackDtos.SubmitRequest request = new FeedbackDtos.SubmitRequest();
        request.setFeedbackType(type);
        request.setContentText(content);
        return request;
    }

    private static FeedbackDtos.TypeItem type(int id, boolean related) {
        var type = new FeedbackDtos.TypeItem();
        type.setId(id);
        type.setName(related ? "设备支持" : "产品建议");
        type.setDeviceRelated(related);
        return type;
    }

    @Test
    void unsupportedUnboundDeviceCanBeSubmittedWithModelAndNoDeviceRow() {
        when(feedbackTypeService.requireEnabled(5)).thenReturn(type(5, true));
        var request = request(5, "无法添加设备");
        request.setDeviceDescription("  某品牌 X2 血糖仪  ");
        service.submit(USER_ID, request);
        var row = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).insert(row.capture());
        assertEquals("某品牌 X2 血糖仪", row.getValue().getDeviceDescription());
        assertNull(row.getValue().getDeviceId());
    }

    @Test
    void ownedDeviceIsLockedAndItsDescriptionIsStoredAsSnapshot() {
        when(feedbackTypeService.requireEnabled(5)).thenReturn(type(5, true));
        UserDevice device = new UserDevice();
        device.setId(10L);
        device.setDeviceName("我的血糖仪");
        device.setProvider("MOCK");
        when(feedbackMapper.lockOwnedDevice(USER_ID, 10L)).thenReturn(device);
        var request = request(5, "同步失败");
        request.setDeviceId(10L);
        service.submit(USER_ID, request);
        var row = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).insert(row.capture());
        assertEquals(10L, row.getValue().getDeviceId());
        assertEquals("我的血糖仪（MOCK）", row.getValue().getDeviceDescription());
    }

    @Test
    void rejectsMissingDeviceDetailsAndForeignOrUnboundDeviceEvenWithDescription() {
        when(feedbackTypeService.requireEnabled(5)).thenReturn(type(5, true));
        assertThrows(BizException.class, () -> service.submit(USER_ID, request(5, "不支持")));
        var request = request(5, "不支持");
        request.setDeviceId(99L);
        request.setDeviceDescription("某型号");
        assertThrows(BizException.class, () -> service.submit(USER_ID, request));
        org.mockito.Mockito.verify(feedbackMapper, org.mockito.Mockito.never())
                .insert(any(Feedback.class));
    }

    @Test
    void rejectsDeviceFieldsForUnrelatedTypeAndOversizeDescription() {
        when(feedbackTypeService.requireEnabled(1)).thenReturn(type(1, false));
        var request = request(1, "建议");
        request.setDeviceId(10L);
        assertThrows(BizException.class, () -> service.submit(USER_ID, request));
        request.setDeviceId(null);
        request.setDeviceDescription("a".repeat(121));
        assertThrows(BizException.class, () -> service.submit(USER_ID, request));
    }

    @Test
    void historyReturnsDictionaryNameAndSnapshotAfterDeviceRemoved() {
        when(feedbackTypeService.names()).thenReturn(Map.of(5, "设备支持"));
        var row = new Feedback();
        row.setId(20L);
        row.setUserId(USER_ID);
        row.setFeedbackType(5);
        row.setDeviceDescription("提交时的品牌型号");
        when(feedbackMapper.selectPage(any(), any()))
                .thenAnswer(
                        inv -> {
                            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Feedback>
                                    page = inv.getArgument(0);
                            page.setRecords(List.of(row));
                            page.setTotal(1);
                            return page;
                        });
        var item = service.page(USER_ID, new PageQuery()).getRecords().getFirst();
        assertEquals("设备支持", item.getFeedbackTypeName());
        assertEquals("提交时的品牌型号", item.getDeviceDescription());
        assertNull(item.getDeviceId());
    }
}
