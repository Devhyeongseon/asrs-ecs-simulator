package com.hyeongseon.asrs.domain;

/**
 * 작업 상태. ECS는 이 상태를 기준으로 같은 작업이 두 번 처리되지 않도록 막는다.
 * WAITING -> REQUESTED -> RUNNING -> DONE / ERROR, 대기 중 취소 시 CANCELED
 */
public enum OrderStatus {
    WAITING, REQUESTED, RUNNING, DONE, ERROR, CANCELED;

    public boolean isActive() {
        return this == WAITING || this == REQUESTED || this == RUNNING;
    }
}
