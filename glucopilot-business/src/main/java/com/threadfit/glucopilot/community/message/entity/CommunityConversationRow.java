package com.threadfit.glucopilot.community.message.entity;

import com.baomidou.mybatisplus.annotation.TableField;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Inbox projection: latest persisted message plus peer identity and unread count. */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommunityConversationRow extends CommunityMessage {
    @TableField(exist = false)
    private Long peerId;

    @TableField(exist = false)
    private Long unreadCount;
}
