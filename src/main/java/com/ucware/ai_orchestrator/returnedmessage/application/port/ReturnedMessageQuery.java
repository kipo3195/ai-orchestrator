package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.time.Instant;

public interface ReturnedMessageQuery {

    // 메시지 책임을 갖는 어플리케이션의 미확인 쪽지 조회를 추상화하는 계약
    MessageSelection selectUnreadMessages(MessageSelectionQuery query);

    record MessageSelectionQuery(
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId,
            Instant offlineSince,
            Instant lookbackSince,
            Instant returnedAt) {
    }

    record MessageSelection(
            String absencePeriodId,
            int unreadCount,
            String messageSelectionRef,
            Instant selectedAt,
            Instant selectionExpiresAt) {
    }
}
