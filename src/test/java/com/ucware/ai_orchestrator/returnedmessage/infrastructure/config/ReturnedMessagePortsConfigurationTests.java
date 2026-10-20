package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessagePermissionChecker;

class ReturnedMessagePortsConfigurationTests {

    @Test
    void allowsReturnedMessagePermissionWhenNoExternalCheckerIsConfigured() {
        ReturnedMessagePermissionChecker checker = new ReturnedMessagePortsConfiguration()
                .allowingReturnedMessagePermissionChecker();

        var request = new ReturnedMessagePermissionChecker.PermissionRequest(
                "tenant-1",
                "user-1",
                "absence-1",
                ReturnedMessagePermissionChecker.Purpose.MESSAGE_SELECTION,
                null);

        assertThat(checker.evaluate(request))
                .isEqualTo(ReturnedMessagePermissionChecker.Decision.ALLOWED);
    }
}
