package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;

public interface ReturnedMessageSelectionRepository {

    ReturnedMessageSelection create(CreateSelection selection, Duration timeToLive);

    Optional<ReturnedMessageSelection> findOwned(
            String messageSelectionRef,
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId);

    Optional<ReturnedMessageSelection> findOwnedActive(
            String messageSelectionRef,
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId);

    record CreateSelection(
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId,
            List<ReturnedMessageSelection.Item> items) {

        public CreateSelection {
            items = List.copyOf(items);
        }
    }
}
