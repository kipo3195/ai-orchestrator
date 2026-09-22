package com.ucware.ai_orchestrator.conversationstart.application.port;

public interface RoomPresencePort {
      /**
     * 사용자가 현재 해당 방에 있고,
     * 현재 활성 세션이 roomSessionId와 동일한지 확인합니다.
     */
    boolean isCurrentSession(
            String userId,
            String roomId,
            String roomSessionId
    );

    /**
     * 사용자가 현재 해당 방에 입장 중인지 확인합니다.
     */
    boolean isPresent(
            String userId,
            String roomId
    );
}
