package com.ucware.ai_orchestrator.returnedmessage.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository.CreateSelection;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;

@JdbcTest
@Import(JdbcReturnedMessageSelectionRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcReturnedMessageSelectionRepositoryTests {

    @Autowired
    private JdbcReturnedMessageSelectionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM returned_message_selection_item");
        jdbcTemplate.update("DELETE FROM returned_message_selection");
        jdbcTemplate.update("DELETE FROM returned_message_request");
    }

    @Test
    void createsUuidSelectionAndLoadsOrderedItemsForOwner() {
        insertLifecycle();

        ReturnedMessageSelection created = repository.create(selection(), Duration.ofMinutes(5));

        assertThatCodeIsUuid(created.messageSelectionRef());
        assertThat(created.items())
                .extracting(ReturnedMessageSelection.Item::sourceItemKey)
                .containsExactly("message-1", "message-2");
        assertThat(created.items())
                .extracting(ReturnedMessageSelection.Item::subject)
                .containsExactly("제목 1", null);
        assertThat(repository.findOwnedActive(
                created.messageSelectionRef(),
                "request-1", "tenant-1", "user-1", "period-1"))
                .contains(created);
        assertThat(repository.findOwnedActive(
                created.messageSelectionRef(),
                "request-1", "tenant-1", "other-user", "period-1"))
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM returned_message_selection_item", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void createIsIdempotentForSameLifecycleRequest() {
        insertLifecycle();

        ReturnedMessageSelection first = repository.create(selection(), Duration.ofMinutes(5));
        ReturnedMessageSelection second = repository.create(selection(), Duration.ofMinutes(5));

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM returned_message_selection", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expiredSelectionIsNotReturnedAsActive() {
        insertLifecycle();
        ReturnedMessageSelection created = repository.create(selection(), Duration.ofMinutes(5));
        jdbcTemplate.update("""
                UPDATE returned_message_selection
                SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE selection_ref = ?
                """, created.messageSelectionRef());

        assertThat(repository.findOwnedActive(
                created.messageSelectionRef(),
                "request-1", "tenant-1", "user-1", "period-1"))
                .isEmpty();
        assertThat(repository.findOwned(
                created.messageSelectionRef(),
                "request-1", "tenant-1", "user-1", "period-1"))
                .isPresent();
    }

    private CreateSelection selection() {
        return new CreateSelection(
                "request-1",
                "tenant-1",
                "user-1",
                "period-1",
                List.of(
                        new ReturnedMessageSelection.Item(
                                "WEBSOCKET_SERVICE", "MESSAGE", "message-1", "제목 1", 0),
                        new ReturnedMessageSelection.Item(
                                "WEBSOCKET_SERVICE", "MESSAGE", "message-2", null, 1)));
    }

    private void insertLifecycle() {
        jdbcTemplate.update("""
                INSERT INTO returned_message_request (
                    tenant_id, request_id, trigger_id, user_id, absence_period_id,
                    offline_since, returned_at, trigger_expires_at,
                    target_ref, connection_type, status
                ) VALUES (?, ?, ?, ?, ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          DATEADD('MINUTE', 10, CURRENT_TIMESTAMP),
                          ?, ?, ?)
                """,
                "tenant-1", "request-1", "trigger-1", "user-1", "period-1",
                "target-1", 1, "DETECTED");
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
