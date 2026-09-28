package com.threadfit.glucopilot.user.feedback;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.AdminFeedbackDtos;
import com.threadfit.glucopilot.user.entity.Feedback;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.FeedbackMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminFeedbackServiceTest {

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private UserAccountMapper userAccountMapper;

    @InjectMocks
    private AdminFeedbackService service;

    @Test
    void pageMapsUserAndMasksContact() {
        Feedback row = feedback(7L, 3L, 1, 0, null);
        row.setGmtCreate(LocalDateTime.of(2026, 9, 23, 9, 30));
        when(feedbackMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Feedback> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });

        UserProfile profile = new UserProfile();
        profile.setUserId(3L);
        profile.setNickname("测试用户");
        when(userProfileMapper.selectList(any())).thenReturn(List.of(profile));

        UserAccount account = new UserAccount();
        account.setId(3L);
        account.setPhone("13812345678");
        when(userAccountMapper.selectBatchIds(any())).thenReturn(List.of(account));

        PageResult<AdminFeedbackDtos.FeedbackItem> result =
                service.page(null, null, null, new PageQuery());

        assertEquals(1, result.getTotal());
        assertEquals("测试用户", result.getRecords().get(0).getUserName());
        assertEquals("138****5678", result.getRecords().get(0).getContact());
        assertEquals("7", result.getRecords().get(0).getId());
    }

    @Test
    void handledStatusRequiresOfficialReply() {
        AdminFeedbackDtos.ReplyRequest request = replyRequest(2, "   ");

        assertThrows(BizException.class, () -> service.reply(9L, 7L, request));
    }

    @Test
    void replyStoresTrimmedTextAndOperator() {
        when(feedbackMapper.selectById(7L)).thenReturn(feedback(7L, 3L, 1, 0, null));

        AdminFeedbackDtos.ReplyResult result = service.reply(
                9L, 7L, replyRequest(2, "  已处理，请重新进入页面查看。  "));

        assertTrue(result.getUpdated());
        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getReplyStatus());
        assertEquals("已处理，请重新进入页面查看。", captor.getValue().getOfficialReply());
        assertEquals(9L, captor.getValue().getRepliedBy());
    }

    private static Feedback feedback(Long id, Long userId, int type, int status, String reply) {
        Feedback row = new Feedback();
        row.setId(id);
        row.setUserId(userId);
        row.setFeedbackType(type);
        row.setContentText("联调反馈");
        row.setReplyStatus(status);
        row.setOfficialReply(reply);
        return row;
    }

    private static AdminFeedbackDtos.ReplyRequest replyRequest(int status, String reply) {
        AdminFeedbackDtos.ReplyRequest request = new AdminFeedbackDtos.ReplyRequest();
        request.setReplyStatus(status);
        request.setOfficialReply(reply);
        return request;
    }
}
