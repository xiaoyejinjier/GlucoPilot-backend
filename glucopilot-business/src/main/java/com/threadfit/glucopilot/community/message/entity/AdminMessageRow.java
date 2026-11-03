package com.threadfit.glucopilot.community.message.entity;

import lombok.Getter;
import lombok.Setter;

/** Read-only joined projection; only CommunityMessage is a persistent table entity. */
@Getter
@Setter
public class AdminMessageRow extends CommunityMessage {
    private String senderNickname;
    private String recipientNickname;
}
