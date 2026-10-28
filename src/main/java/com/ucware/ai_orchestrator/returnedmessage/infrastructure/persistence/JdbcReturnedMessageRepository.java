package com.ucware.ai_orchestrator.returnedmessage.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePersistenceException;
import com.ucware.ai_orchestrator.returnedmessage.application.port.LifecycleCreationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageFailureStage;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

@Repository
public class JdbcReturnedMessageRepository implements ReturnedMessageRepository {

    private static final String NON_TERMINAL_STATUSES =
            "('DETECTED', 'AWAITING_CONFIRMATION', 'PROCESSING', 'DELIVERY_PENDING')";

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JdbcReturnedMessageRepository(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public Instant currentTime() {
        try {
            return jdbcClient.sql("SELECT CURRENT_TIMESTAMP")
                    .query((resultSet, rowNumber) -> instant(resultSet, 1))
                    .single();
        } catch (DataAccessException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to read lifecycle database time", exception);
        }
    }

    @Override
    public LifecycleCreationResult create(ReturnedMessageRequest request) {
        try {
            transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                            INSERT INTO returned_message_request (
                                tenant_id, request_id, trigger_id, user_id, absence_period_id,
                                offline_since, returned_at, trigger_expires_at,
                                target_ref, connection_type, status, created_at, updated_at
                            ) VALUES (
                                :tenantId, :requestId, :triggerId, :userId, :absencePeriodId,
                                :offlineSince, :returnedAt, :triggerExpiresAt,
                                :targetRef, :connectionType, 'DETECTED',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                            )
                            """)
                    .param("tenantId", request.tenantId())
                    .param("requestId", request.requestId())
                    .param("triggerId", request.triggerId())
                    .param("userId", request.userId())
                    .param("absencePeriodId", request.absencePeriodId())
                    .param("offlineSince", request.offlineSince())
                    .param("returnedAt", request.returnedAt())
                    .param("triggerExpiresAt", request.triggerExpiresAt())
                    .param("targetRef", request.routingRef().clientSessionId())
                    .param("connectionType", request.routingRef().connectionType())
                    .update());
            return LifecycleCreationResult.created(findOwned(
                    request.tenantId(), request.userId(), request.requestId()).orElseThrow());
        } catch (DataIntegrityViolationException exception) {
            return resolveCollision(request);
        } catch (DataAccessException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to create Returned Message lifecycle", exception);
        }
    }

    private LifecycleCreationResult resolveCollision(ReturnedMessageRequest request) {
        Optional<ReturnedMessageLifecycle> byTrigger = findByTrigger(
                request.tenantId(), request.triggerId());
        if (byTrigger.isPresent()) {
            ReturnedMessageLifecycle existing = byTrigger.get();
            if (existing.userId().equals(request.userId())
                    && existing.absencePeriodId().equals(request.absencePeriodId())) {
                return LifecycleCreationResult.alreadyExists(existing);
            }
            return LifecycleCreationResult.conflict();
        }

        Optional<ReturnedMessageLifecycle> byRequest = findByRequest(
                request.tenantId(), request.requestId());
        if (byRequest.isPresent()) {
            ReturnedMessageLifecycle existing = byRequest.get();
            if (existing.userId().equals(request.userId())
                    && existing.absencePeriodId().equals(request.absencePeriodId())
                    && existing.triggerId().equals(request.triggerId())) {
                return LifecycleCreationResult.alreadyExists(existing);
            }
            return LifecycleCreationResult.conflict();
        }

        Optional<ReturnedMessageLifecycle> byPeriod = findByPeriod(
                request.tenantId(), request.userId(), request.absencePeriodId());
        if (byPeriod.isPresent()) {
            return LifecycleCreationResult.alreadyExists(byPeriod.get());
        }

        throw new ReturnedMessagePersistenceException(
                "Lifecycle constraint violation could not be correlated", null);
    }

    @Override
    public Optional<ReturnedMessageLifecycle> findOwned(
            String tenantId, String userId, String requestId) {
        return jdbcClient.sql(BASE_SELECT + """
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                          AND request_id = :requestId
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("requestId", requestId)
                .query(this::mapRow)
                .optional();
    }

    private Optional<ReturnedMessageLifecycle> findByPeriod(
            String tenantId, String userId, String absencePeriodId) {
        return jdbcClient.sql(BASE_SELECT + """
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                          AND absence_period_id = :absencePeriodId
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("absencePeriodId", absencePeriodId)
                .query(this::mapRow)
                .optional();
    }

    private Optional<ReturnedMessageLifecycle> findByTrigger(String tenantId, String triggerId) {
        return jdbcClient.sql(BASE_SELECT + """
                        WHERE tenant_id = :tenantId
                          AND trigger_id = :triggerId
                        """)
                .param("tenantId", tenantId)
                .param("triggerId", triggerId)
                .query(this::mapRow)
                .optional();
    }

    private Optional<ReturnedMessageLifecycle> findByRequest(String tenantId, String requestId) {
        return jdbcClient.sql(BASE_SELECT + """
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                        """)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public boolean markNoMessages(String tenantId, String requestId) {
        return updateStatus(tenantId, requestId, "DETECTED", "SKIPPED_NO_MESSAGES") == 1;
    }

    @Override
    public boolean awaitConfirmation(
            String tenantId,
            String requestId,
            String messageSelectionRef,
            int unreadCount,
            Instant selectedAt,
            Instant selectionExpiresAt,
            String confirmationId,
            Duration confirmationTimeout) {
        Boolean updated = transactionTemplate.execute(status -> {
            Instant databaseNow = currentTime();
            Instant confirmationExpiresAt = databaseNow.plus(confirmationTimeout);
            if (selectionExpiresAt.isBefore(confirmationExpiresAt)) {
                confirmationExpiresAt = selectionExpiresAt;
            }
            if (!confirmationExpiresAt.isAfter(databaseNow)) {
                return false;
            }
            return jdbcClient.sql("""
                            UPDATE returned_message_request
                            SET status = 'AWAITING_CONFIRMATION',
                                message_selection_ref = :messageSelectionRef,
                                unread_count = :unreadCount,
                                selected_at = :selectedAt,
                                selection_expires_at = :selectionExpiresAt,
                                confirmation_id = :confirmationId,
                                confirmation_expires_at = :confirmationExpiresAt,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE tenant_id = :tenantId
                              AND request_id = :requestId
                              AND status = 'DETECTED'
                            """)
                    .param("messageSelectionRef", messageSelectionRef)
                    .param("unreadCount", unreadCount)
                    .param("selectedAt", selectedAt)
                    .param("selectionExpiresAt", selectionExpiresAt)
                    .param("confirmationId", confirmationId)
                    .param("confirmationExpiresAt", confirmationExpiresAt)
                    .param("tenantId", tenantId)
                    .param("requestId", requestId)
                    .update() == 1;
        });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public boolean failDetection(String tenantId, String requestId, String safeReason) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'FAILED',
                            failure_stage = 'DETECTION',
                            failure_reason = :failureReason,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND status = 'DETECTED'
                        """)
                .param("failureReason", safeReason)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .update() == 1;
    }

    @Override
    public boolean decline(
            String tenantId,
            String userId,
            String requestId,
            String absencePeriodId,
            String confirmationId,
            String messageSelectionRef) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'DECLINED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                          AND request_id = :requestId
                          AND absence_period_id = :absencePeriodId
                          AND confirmation_id = :confirmationId
                          AND message_selection_ref = :messageSelectionRef
                          AND status = 'AWAITING_CONFIRMATION'
                          AND confirmation_expires_at > CURRENT_TIMESTAMP
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("requestId", requestId)
                .param("absencePeriodId", absencePeriodId)
                .param("confirmationId", confirmationId)
                .param("messageSelectionRef", messageSelectionRef)
                .update() == 1;
    }

    @Override
    public boolean blockByPolicy(
            String tenantId,
            String userId,
            String requestId,
            String confirmationId,
            String safeReason) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'BLOCKED_BY_POLICY',
                            failure_reason = :failureReason,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                          AND request_id = :requestId
                          AND confirmation_id = :confirmationId
                          AND status = 'AWAITING_CONFIRMATION'
                          AND confirmation_expires_at > CURRENT_TIMESTAMP
                        """)
                .param("failureReason", safeReason)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("requestId", requestId)
                .param("confirmationId", confirmationId)
                .update() == 1;
    }

    @Override
    public boolean startProcessing(
            String tenantId,
            String userId,
            String requestId,
            String confirmationId,
            String executionId) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'PROCESSING',
                            execution_id = :executionId,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                          AND request_id = :requestId
                          AND confirmation_id = :confirmationId
                          AND status = 'AWAITING_CONFIRMATION'
                          AND confirmation_expires_at > CURRENT_TIMESTAMP
                        """)
                .param("executionId", executionId)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("requestId", requestId)
                .param("confirmationId", confirmationId)
                .update() == 1;
    }

    @Override
    public boolean expireIfDue(String tenantId, String userId, String requestId) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'EXPIRED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                          AND request_id = :requestId
                          AND status = 'AWAITING_CONFIRMATION'
                          AND confirmation_expires_at <= CURRENT_TIMESTAMP
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("requestId", requestId)
                .update() == 1;
    }

    @Override
    public boolean expireTriggerIfDue(String tenantId, String requestId) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'EXPIRED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND status = 'DETECTED'
                          AND trigger_expires_at <= CURRENT_TIMESTAMP
                        """)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .update() == 1;
    }

    @Override
    public int expireConfirmations(int batchSize) {
        List<LifecycleKey> candidates = jdbcClient.sql("""
                        SELECT tenant_id, request_id
                        FROM returned_message_request
                        WHERE status = 'AWAITING_CONFIRMATION'
                          AND confirmation_expires_at <= CURRENT_TIMESTAMP
                        ORDER BY confirmation_expires_at, tenant_id, request_id
                        LIMIT %d
                        """.formatted(positiveBatchSize(batchSize)))
                .query((resultSet, rowNumber) -> new LifecycleKey(
                        resultSet.getString("tenant_id"),
                        resultSet.getString("request_id")))
                .list();

        int expired = 0;
        for (LifecycleKey candidate : candidates) {
            expired += jdbcClient.sql("""
                            UPDATE returned_message_request
                            SET status = 'EXPIRED',
                                updated_at = CURRENT_TIMESTAMP
                            WHERE tenant_id = :tenantId
                              AND request_id = :requestId
                              AND status = 'AWAITING_CONFIRMATION'
                              AND confirmation_expires_at <= CURRENT_TIMESTAMP
                            """)
                    .param("tenantId", candidate.tenantId())
                    .param("requestId", candidate.requestId())
                    .update();
        }
        return expired;
    }

    @Override
    public boolean executionSucceeded(
            String tenantId,
            String requestId,
            String executionId,
            ReturnedMessageTopicsResult result,
            String deliveryId) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'DELIVERY_PENDING',
                            result_payload = :resultPayload,
                            delivery_id = :deliveryId,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND status = 'PROCESSING'
                          AND execution_id = :executionId
                        """)
                .param("resultPayload", writeResult(result))
                .param("deliveryId", deliveryId)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .param("executionId", executionId)
                .update() == 1;
    }

    @Override
    public boolean executionFailed(
            String tenantId,
            String requestId,
            String executionId,
            ReturnedMessageFailureStage failureStage,
            String safeReason) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = 'FAILED',
                            failure_stage = :failureStage,
                            failure_reason = :failureReason,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND status = 'PROCESSING'
                          AND execution_id = :executionId
                        """)
                .param("failureStage", failureStage.name())
                .param("failureReason", safeReason)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .param("executionId", executionId)
                .update() == 1;
    }

    @Override
    public boolean executionCancelled(String tenantId, String requestId, String executionId) {
        return correlatedStatusUpdate(
                tenantId, requestId, executionId, "PROCESSING", "CANCELLED", null, null);
    }

    @Override
    public boolean deliveryCompleted(
            String tenantId, String requestId, String executionId, String deliveryId) {
        return correlatedStatusUpdate(
                tenantId, requestId, executionId, "DELIVERY_PENDING", "COMPLETED",
                deliveryId, null);
    }

    @Override
    public boolean deliveryFailed(
            String tenantId,
            String requestId,
            String executionId,
            String deliveryId,
            String safeReason) {
        return correlatedStatusUpdate(
                tenantId, requestId, executionId, "DELIVERY_PENDING", "DELIVERY_FAILED",
                deliveryId, safeReason);
    }

    @Override
    public List<ReturnedMessageLifecycle> findRecoverable(int batchSize) {
        return jdbcClient.sql(BASE_SELECT + """
                        WHERE status IN %s
                          AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP)
                        ORDER BY updated_at, tenant_id, request_id
                        LIMIT %d
                        """.formatted(NON_TERMINAL_STATUSES, positiveBatchSize(batchSize)))
                .query(this::mapRow)
                .list();
    }

    @Override
    public boolean tryClaim(
            String tenantId,
            String requestId,
            String ownerToken,
            Duration leaseDuration) {
        Boolean claimed = transactionTemplate.execute(status -> {
            Instant claimUntil = currentTime().plus(leaseDuration);
            return jdbcClient.sql("""
                            UPDATE returned_message_request
                            SET claim_owner = :ownerToken,
                                claim_until = :claimUntil,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE tenant_id = :tenantId
                              AND request_id = :requestId
                              AND status IN %s
                              AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP)
                            """.formatted(NON_TERMINAL_STATUSES))
                    .param("ownerToken", ownerToken)
                    .param("claimUntil", claimUntil)
                    .param("tenantId", tenantId)
                    .param("requestId", requestId)
                    .update() == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    @Override
    public void releaseClaim(String tenantId, String requestId, String ownerToken) {
        jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET claim_owner = NULL,
                            claim_until = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND claim_owner = :ownerToken
                        """)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .param("ownerToken", ownerToken)
                .update();
    }

    private int updateStatus(
            String tenantId, String requestId, String expectedStatus, String nextStatus) {
        return jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = :nextStatus,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND status = :expectedStatus
                        """)
                .param("nextStatus", nextStatus)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .param("expectedStatus", expectedStatus)
                .update();
    }

    private boolean correlatedStatusUpdate(
            String tenantId,
            String requestId,
            String executionId,
            String expectedStatus,
            String nextStatus,
            String deliveryId,
            String failureReason) {
        String deliveryCondition = deliveryId == null ? "" : " AND delivery_id = :deliveryId";
        String failureAssignment = failureReason == null
                ? ""
                : ", failure_stage = 'DELIVERY', failure_reason = :failureReason";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        UPDATE returned_message_request
                        SET status = :nextStatus%s,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND request_id = :requestId
                          AND status = :expectedStatus
                          AND execution_id = :executionId%s
                        """.formatted(failureAssignment, deliveryCondition))
                .param("nextStatus", nextStatus)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .param("expectedStatus", expectedStatus)
                .param("executionId", executionId);
        if (deliveryId != null) {
            statement = statement.param("deliveryId", deliveryId);
        }
        if (failureReason != null) {
            statement = statement.param("failureReason", failureReason);
        }
        return statement.update() == 1;
    }

    private ReturnedMessageLifecycle mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String tenantId = resultSet.getString("tenant_id");
        String userId = resultSet.getString("user_id");
        String absencePeriodId = resultSet.getString("absence_period_id");
        RoutingRef routingRef = RoutingRef.forClientSession(
                tenantId,
                userId,
                resultSet.getString("target_ref"),
                new RoutingScope("ABSENCE_PERIOD", absencePeriodId),
                resultSet.getObject("connection_type", Integer.class));
        return new ReturnedMessageLifecycle(
                resultSet.getString("request_id"),
                resultSet.getString("trigger_id"),
                tenantId,
                userId,
                absencePeriodId,
                instant(resultSet, "offline_since"),
                instant(resultSet, "returned_at"),
                instant(resultSet, "trigger_expires_at"),
                routingRef,
                ReturnedMessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("message_selection_ref"),
                resultSet.getObject("unread_count", Integer.class),
                nullableInstant(resultSet, "selected_at"),
                nullableInstant(resultSet, "selection_expires_at"),
                resultSet.getString("confirmation_id"),
                nullableInstant(resultSet, "confirmation_expires_at"),
                resultSet.getString("execution_id"),
                readResult(resultSet.getString("result_payload")),
                resultSet.getString("delivery_id"),
                enumValue(ReturnedMessageFailureStage.class,
                        resultSet.getString("failure_stage")),
                resultSet.getString("failure_reason"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static int positiveBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return batchSize;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return toInstant(resultSet.getObject(column));
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        return toInstant(resultSet.getObject(column));
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : toInstant(value);
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

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private String writeResult(ReturnedMessageTopicsResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to serialize Returned Message result", exception);
        }
    }

    private ReturnedMessageTopicsResult readResult(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, ReturnedMessageTopicsResult.class);
        } catch (JsonProcessingException exception) {
            throw new ReturnedMessagePersistenceException(
                    "Failed to deserialize Returned Message result", exception);
        }
    }

    private record LifecycleKey(String tenantId, String requestId) {
    }

    private static final String BASE_SELECT = """
            SELECT tenant_id, request_id, trigger_id, user_id, absence_period_id,
                   offline_since, returned_at, trigger_expires_at,
                   target_ref, connection_type, status,
                   message_selection_ref, unread_count, selected_at, selection_expires_at,
                   confirmation_id, confirmation_expires_at,
                   execution_id, result_payload, delivery_id,
                   failure_stage, failure_reason, created_at, updated_at
            FROM returned_message_request
            """;
}
