package com.ucware.ai_orchestrator.returnedmessage.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePersistenceException;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;

@Repository
public class JdbcReturnedMessageSelectionRepository
        implements ReturnedMessageSelectionRepository {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcReturnedMessageSelectionRepository(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReturnedMessageSelection create(CreateSelection selection, Duration timeToLive) {
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("Selection timeToLive must be positive");
        }
        if (selection.items().isEmpty()) {
            throw new IllegalArgumentException("Selection items must not be empty");
        }

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                Instant selectedAt = currentTime();
                Optional<ReturnedMessageSelection> existing = findByRequest(selection);
                if (existing.isPresent() && existing.get().expiresAt().isAfter(selectedAt)) {
                    return existing.get();
                }
                existing.ifPresent(value -> delete(value.messageSelectionRef()));

                ReturnedMessageSelection created = new ReturnedMessageSelection(
                        UUID.randomUUID().toString(),
                        selection.requestId(),
                        selection.tenantId(),
                        selection.userId(),
                        selection.absencePeriodId(),
                        selectedAt,
                        selectedAt.plus(timeToLive),
                        selection.items());
                insert(created);
                return created;
            }));
        } catch (DataIntegrityViolationException exception) {
            return findByRequest(selection)
                    .orElseThrow(() -> new ReturnedMessagePersistenceException(
                            "Failed to correlate concurrent message selection creation",
                            exception));
        } catch (DataAccessException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to create Returned Message selection", exception);
        }
    }

    @Override
    public Optional<ReturnedMessageSelection> findOwned(
            String messageSelectionRef,
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId) {
        try {
            return find(messageSelectionRef, requestId, tenantId, userId, absencePeriodId, false);
        } catch (DataAccessException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to read Returned Message selection", exception);
        }
    }

    @Override
    public Optional<ReturnedMessageSelection> findOwnedActive(
            String messageSelectionRef,
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId) {
        try {
            return find(messageSelectionRef, requestId, tenantId, userId, absencePeriodId, true);
        } catch (DataAccessException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to read active Returned Message selection", exception);
        }
    }

    private Optional<ReturnedMessageSelection> find(
            String messageSelectionRef,
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId,
            boolean activeOnly) {
        String activeCondition = activeOnly ? " AND expires_at > CURRENT_TIMESTAMP" : "";
        return jdbcClient.sql("""
                        SELECT selection_ref, request_id, tenant_id, user_id, absence_period_id,
                               selected_at, expires_at
                        FROM returned_message_selection
                        WHERE selection_ref = :selectionRef
                          AND request_id = :requestId
                          AND tenant_id = :tenantId
                          AND user_id = :userId
                          AND absence_period_id = :absencePeriodId%s
                        """.formatted(activeCondition))
                .param("selectionRef", messageSelectionRef)
                .param("requestId", requestId)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("absencePeriodId", absencePeriodId)
                .query(this::mapSelectionRow)
                .optional()
                .map(this::withItems);
    }

    private Optional<ReturnedMessageSelection> findByRequest(CreateSelection selection) {
        return jdbcClient.sql("""
                        SELECT selection_ref, request_id, tenant_id, user_id, absence_period_id,
                               selected_at, expires_at
                        FROM returned_message_selection
                        WHERE request_id = :requestId
                          AND tenant_id = :tenantId
                          AND user_id = :userId
                          AND absence_period_id = :absencePeriodId
                        """)
                .param("requestId", selection.requestId())
                .param("tenantId", selection.tenantId())
                .param("userId", selection.userId())
                .param("absencePeriodId", selection.absencePeriodId())
                .query(this::mapSelectionRow)
                .optional()
                .map(this::withItems);
    }

    private ReturnedMessageSelection withItems(SelectionRow row) {
        List<ReturnedMessageSelection.Item> items = jdbcClient.sql("""
                        SELECT source_system, source_item_type, source_item_key,
                               source_item_subject, sequence_no
                        FROM returned_message_selection_item
                        WHERE selection_ref = :selectionRef
                        ORDER BY sequence_no
                        """)
                .param("selectionRef", row.messageSelectionRef())
                .query((resultSet, rowNumber) -> new ReturnedMessageSelection.Item(
                        resultSet.getString("source_system"),
                        resultSet.getString("source_item_type"),
                        resultSet.getString("source_item_key"),
                        resultSet.getString("source_item_subject"),
                        resultSet.getInt("sequence_no")))
                .list();
        return new ReturnedMessageSelection(
                row.messageSelectionRef(),
                row.requestId(),
                row.tenantId(),
                row.userId(),
                row.absencePeriodId(),
                row.selectedAt(),
                row.expiresAt(),
                items);
    }

    private void insert(ReturnedMessageSelection selection) {
        jdbcClient.sql("""
                        INSERT INTO returned_message_selection (
                            selection_ref, request_id, tenant_id, user_id, absence_period_id,
                            selected_at, expires_at, created_at
                        ) VALUES (
                            :selectionRef, :requestId, :tenantId, :userId, :absencePeriodId,
                            :selectedAt, :expiresAt, CURRENT_TIMESTAMP
                        )
                        """)
                .param("selectionRef", selection.messageSelectionRef())
                .param("requestId", selection.requestId())
                .param("tenantId", selection.tenantId())
                .param("userId", selection.userId())
                .param("absencePeriodId", selection.absencePeriodId())
                .param("selectedAt", selection.selectedAt())
                .param("expiresAt", selection.expiresAt())
                .update();

        for (ReturnedMessageSelection.Item item : selection.items()) {
            jdbcClient.sql("""
                            INSERT INTO returned_message_selection_item (
                                selection_ref, sequence_no, source_system,
                                source_item_type, source_item_key,
                                source_item_subject, created_at
                            ) VALUES (
                                :selectionRef, :sequence, :sourceSystem,
                                :sourceItemType, :sourceItemKey,
                                :sourceItemSubject, CURRENT_TIMESTAMP
                            )
                            """)
                    .param("selectionRef", selection.messageSelectionRef())
                    .param("sequence", item.sequence())
                    .param("sourceSystem", item.sourceSystem())
                    .param("sourceItemType", item.sourceItemType())
                    .param("sourceItemKey", item.sourceItemKey())
                    .param("sourceItemSubject", item.subject())
                    .update();
        }
    }

    private void delete(String messageSelectionRef) {
        jdbcClient.sql("""
                        DELETE FROM returned_message_selection
                        WHERE selection_ref = :selectionRef
                        """)
                .param("selectionRef", messageSelectionRef)
                .update();
    }

    private Instant currentTime() {
        return jdbcClient.sql("SELECT CURRENT_TIMESTAMP")
                .query((resultSet, rowNumber) -> instant(resultSet, 1))
                .single();
    }

    private SelectionRow mapSelectionRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SelectionRow(
                resultSet.getString("selection_ref"),
                resultSet.getString("request_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("user_id"),
                resultSet.getString("absence_period_id"),
                instant(resultSet, "selected_at"),
                instant(resultSet, "expires_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return toInstant(resultSet.getObject(column));
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        return toInstant(resultSet.getObject(column));
    }

    private static Instant toInstant(Object value) throws SQLException {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new SQLException("Unsupported timestamp value: " + value);
    }

    private record SelectionRow(
            String messageSelectionRef,
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId,
            Instant selectedAt,
            Instant expiresAt) {
    }
}
