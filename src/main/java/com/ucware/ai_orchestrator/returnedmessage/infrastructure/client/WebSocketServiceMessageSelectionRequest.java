package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import java.util.List;

public record WebSocketServiceMessageSelectionRequest(
        String sqlName,
        String startDate,
        String endDate,
        String searchDate,
        String urgent,
        String keyword,
        List<String> searchOpt,
        String labelCode,
        int reqcount,
        int reqpage) {

    public WebSocketServiceMessageSelectionRequest {
        searchOpt = List.copyOf(searchOpt);
    }
}
