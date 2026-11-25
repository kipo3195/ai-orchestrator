package com.ucware.ai_orchestrator.returnedmessage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class ReturnedMessageClientEventStateTests {

    @ParameterizedTest
    @EnumSource(value = ReturnedMessageStatus.class, names = {
            "AWAITING_CONFIRMATION", "PROCESSING", "DECLINED", "EXPIRED", "CANCELLED"
    })
    void preservesStatesWhoseInternalAndPublicNamesMatch(ReturnedMessageStatus status) {
        assertThat(ReturnedMessageClientEventState.from(status).name()).isEqualTo(status.name());
    }

    @Test
    void mapsInternalOnlyStatesToPublicStates() {
        assertThat(ReturnedMessageClientEventState.from(ReturnedMessageStatus.DELIVERY_PENDING))
                .isEqualTo(ReturnedMessageClientEventState.TOPICS_READY);
        assertThat(ReturnedMessageClientEventState.from(ReturnedMessageStatus.COMPLETED))
                .isEqualTo(ReturnedMessageClientEventState.TOPICS_READY);
        assertThat(ReturnedMessageClientEventState.from(ReturnedMessageStatus.BLOCKED_BY_POLICY))
                .isEqualTo(ReturnedMessageClientEventState.UNAVAILABLE);
        assertThat(ReturnedMessageClientEventState.from(ReturnedMessageStatus.SKIPPED_NO_MESSAGES))
                .isEqualTo(ReturnedMessageClientEventState.UNAVAILABLE);
        assertThat(ReturnedMessageClientEventState.from(ReturnedMessageStatus.FAILED))
                .isEqualTo(ReturnedMessageClientEventState.FAILED);
        assertThat(ReturnedMessageClientEventState.from(ReturnedMessageStatus.DELIVERY_FAILED))
                .isEqualTo(ReturnedMessageClientEventState.FAILED);
    }

    @Test
    void detectedHasNoPublicState() {
        assertThatThrownBy(() -> ReturnedMessageClientEventState.from(
                ReturnedMessageStatus.DETECTED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
