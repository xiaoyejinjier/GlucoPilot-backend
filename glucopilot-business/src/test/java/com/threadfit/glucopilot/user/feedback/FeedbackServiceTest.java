package com.threadfit.glucopilot.user.feedback;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private FeedbackMapper feedbackMapper;

    @InjectMocks
    private FeedbackService service;

    @Test
    void rejectsUnknownFeedbackType() {
        assertThrows(BizException.class, () -> service.submit(USER_ID, request(9, "内容")));
    }

    @Test
    void submitStoresTrimmedContentAndReceivedStatus() {
        when(feedbackMapper.insert(any(Feedback.class))).thenAnswer(inv -> {
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

        when(feedbackMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Feedback> page = inv.getArgument(0);
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

        when(feedbackMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Feedback> page = inv.getArgument(0);
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
}
