package com.threadfit.glucopilot.community.message.realtime;

/** 事务内发布的失效通知：不携带正文；只有提交成功后才转发到在线连接。 */
public record DirectMessageChanged(long senderId, long recipientId, String messageId, String kind) {}
