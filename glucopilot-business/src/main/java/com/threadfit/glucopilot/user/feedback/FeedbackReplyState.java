package com.threadfit.glucopilot.user.feedback;

import com.threadfit.glucopilot.user.entity.Feedback;
import org.springframework.util.StringUtils;

/** 回复成功即完成；旧处理中、关闭记录也按实际回复归类。 */
public final class FeedbackReplyState {
    public static final int PENDING = 0;
    public static final int DONE = 2;
    public static final String PENDING_SQL = "COALESCE(CHAR_LENGTH(TRIM(official_reply)),0)=0";
    public static final String DONE_SQL = "COALESCE(CHAR_LENGTH(TRIM(official_reply)),0)>0";

    private FeedbackReplyState() {}

    public static int of(Feedback feedback) {
        return StringUtils.hasText(feedback.getOfficialReply()) ? DONE : PENDING;
    }
}
