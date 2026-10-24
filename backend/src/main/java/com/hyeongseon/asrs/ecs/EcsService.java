package com.hyeongseon.asrs.ecs;

import com.hyeongseon.asrs.domain.*;
import com.hyeongseon.asrs.plc.VirtualPlc;
import com.hyeongseon.asrs.repository.LocationRepository;
import com.hyeongseon.asrs.repository.WorkOrderRepository;
import com.hyeongseon.asrs.service.EventLogService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ECS: WMS 작업목록을 읽어 PLC에 제어 명령을 내리고, 핸드셰이크로 완료를 받아 WMS 재고에 반영한다.
 *
 *  IDLE ─(대기 작업 발견, D100 ON)→ WAIT_ACK ─(D200 ACK, D100 OFF)→ RUNNING
 *  RUNNING ─(D202 DONE, 재고 반영, D105 ON)→ WAIT_DONE_CLEAR ─(D202 OFF, D105 OFF)→ IDLE
 *  RUNNING ─(D203 ERR, D106 ON)→ WAIT_ERR_CLEAR ─(D203 OFF, D106 OFF)→ IDLE
 *  WAIT_ACK ─(8초 무응답)→ 타임아웃 에러 → IDLE
 */
@Service
public class EcsService {

    public enum Phase { IDLE, WAIT_ACK, RUNNING, WAIT_DONE_CLEAR, WAIT_ERR_CLEAR }

    private static final long ACK_TIMEOUT_MS = 8000;

    private final WorkOrderRepository orderRepository;
    private final LocationRepository locationRepository;
    private final VirtualPlc plc;
    private final EventLogService log;

    private Phase phase = Phase.IDLE;
    private Long currentOrderId;
    private long requestedAt;
    private boolean clearingStrayDone;
    private volatile boolean paused;

    public EcsService(WorkOrderRepository orderRepository, LocationRepository locationRepository,
                      VirtualPlc plc, EventLogService log) {
        this.orderRepository = orderRepository;
        this.locationRepository = locationRepository;
        this.plc = plc;
        this.log = log;
    }

    @Scheduled(fixedDelay = 300)
    @Transactional
    public synchronized void cycle() {
        switch (phase) {
            case IDLE -> onIdle();
            case WAIT_ACK -> onWaitAck();
            case RUNNING -> onRunning();
            case WAIT_DONE_CLEAR -> {
                if (!plc.isDone()) {
                    plc.setDoneAckBit(false);
                    finish();
                }
            }
            case WAIT_ERR_CLEAR -> {
                if (!plc.isErr()) {
                    plc.setErrResetBit(false);
                    finish();
                }
            }
        }
    }

    private void onIdle() {
        // 작업을 내리지 않았는데 완료 신호가 올라온 경우: 중복 완료 신호
        if (plc.isDone()) {
            handleUnexpectedDone();
            return;
        }

        // 일시정지 중에는 새 작업을 내리지 않는다 (진행 중 작업은 끝까지 처리)
        if (paused) return;

        Optional<WorkOrder> next = orderRepository.findFirstByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING);
        if (next.isEmpty()) return;
        WorkOrder order = next.get();

        // 설비에 내리기 전 WMS 재고와 목표 위치를 다시 검증
        Location loc = locationRepository.findByRowNoAndColNo(order.getTargetRow(), order.getTargetCol()).orElse(null);
        String invalid = validate(order, loc);
        if (invalid != null) {
            order.changeStatus(OrderStatus.ERROR, invalid);
            log.error("ECS", order.getOrderNo() + " 명령 전 검증 실패: " + invalid);
            return;
        }

        plc.writeCommand(order.getType().getPlcCode(), order.getTargetRow(), order.getTargetCol(),
                order.getId().intValue());
        plc.setCmdBit(true);
        order.changeStatus(OrderStatus.REQUESTED, "PLC 작업요청");
        currentOrderId = order.getId();
        requestedAt = System.currentTimeMillis();
        phase = Phase.WAIT_ACK;
        log.info("ECS", String.format("%s 작업요청 D100=1 (유형 %d, 행 %d, 열 %d, ID %d)",
                order.getOrderNo(), order.getType().getPlcCode(), order.getTargetRow(),
                order.getTargetCol(), order.getId()));
    }

    private void onWaitAck() {
        WorkOrder order = current();
        if (order == null) { reset(); return; }

        // 요청 대기 중에 다른 작업 ID의 완료 신호가 들어오면 재고에 반영하지 않고 신호만 정리
        if (plc.isDone() && plc.getEchoJobId() != currentOrderId.intValue()) {
            if (!clearingStrayDone) {
                log.warn("ECS", "작업요청 대기 중 다른 작업(ID " + plc.getEchoJobId() + ")의 완료 신호 감지 → 무시하고 정리");
            }
            clearingStrayDone = true;
            plc.setDoneAckBit(true);
            return;
        }
        if (clearingStrayDone && !plc.isDone()) {
            clearingStrayDone = false;
            plc.setDoneAckBit(false);
        }

        if (plc.isAck() && plc.getEchoJobId() == currentOrderId.intValue()) {
            plc.setCmdBit(false);
            order.changeStatus(OrderStatus.RUNNING, "설비 동작 중");
            phase = Phase.RUNNING;
            log.info("ECS", order.getOrderNo() + " PLC 수신확인 D200=1 → 요청 비트 OFF, 동작 시작");
        } else if (System.currentTimeMillis() - requestedAt > ACK_TIMEOUT_MS) {
            plc.setCmdBit(false);
            order.changeStatus(OrderStatus.ERROR, "PLC 응답 없음 (타임아웃)");
            log.error("ECS", order.getOrderNo() + " PLC 수신확인 타임아웃 → 요청 비트 OFF, 작업 에러 처리");
            finish();
        }
    }

    private void onRunning() {
        WorkOrder order = current();
        if (order == null) { reset(); return; }

        if (plc.isErr()) {
            String reason = plc.getErrCode() == VirtualPlc.ERR_SENSOR_NOT_DETECTED
                    ? "화물 미감지 (센서)" : "PLC 이상 코드 " + plc.getErrCode();
            order.changeStatus(OrderStatus.ERROR, reason);
            plc.setErrResetBit(true);
            phase = Phase.WAIT_ERR_CLEAR;
            log.error("PLC", order.getOrderNo() + " 이상 발생 D203=1: " + reason + " → 에러리셋 D106=1");
            return;
        }

        if (plc.isDone() && plc.getEchoJobId() == currentOrderId.intValue()) {
            complete(order);
            plc.setDoneAckBit(true);
            phase = Phase.WAIT_DONE_CLEAR;
        }
    }

    /** 완료 처리. 이미 DONE이면 재고를 다시 건드리지 않는다 (중복 차감 방지). */
    private void complete(WorkOrder order) {
        if (order.getStatus() == OrderStatus.DONE) {
            log.warn("ECS", order.getOrderNo() + " 이미 완료된 작업 → 재고 반영 생략");
            return;
        }
        Location loc = locationRepository.findByRowNoAndColNo(order.getTargetRow(), order.getTargetCol()).orElseThrow();
        if (order.getType() == OrderType.INBOUND) {
            loc.store(order.getPalletNo(), order.getItemName(), order.getLotId());
        } else {
            loc.clear();
        }
        order.changeStatus(OrderStatus.DONE, "완료");
        log.info("ECS", order.getOrderNo() + " 작업완료 D202=1 → WMS 재고 반영 (" + loc.getId() + "), 완료확인 D105=1");
    }

    private void handleUnexpectedDone() {
        int echo = plc.getEchoJobId();
        WorkOrder order = orderRepository.findById((long) echo).orElse(null);
        String name = order != null ? order.getOrderNo() : "ID " + echo;
        if (order != null && order.getStatus() == OrderStatus.DONE) {
            log.warn("ECS", "중복 완료 신호 감지: " + name + " 는 이미 완료됨 → 재고 반영 생략, 신호만 정리");
        } else {
            log.warn("ECS", "요청하지 않은 완료 신호 감지: " + name + " → 재고 반영 없이 신호 정리");
        }
        plc.setDoneAckBit(true);
        phase = Phase.WAIT_DONE_CLEAR;
    }

    private String validate(WorkOrder order, Location loc) {
        if (loc == null) return "존재하지 않는 랙 위치";
        if (order.getType() == OrderType.INBOUND && !loc.isEmpty()) {
            return "목표 랙 " + loc.getId() + " 이미 사용 중";
        }
        if (order.getType() == OrderType.OUTBOUND
                && (loc.isEmpty() || !order.getPalletNo().equals(loc.getPalletNo()))) {
            return "랙 " + loc.getId() + " 재고와 출고 팔레트 불일치";
        }
        return null;
    }

    private WorkOrder current() {
        return currentOrderId == null ? null : orderRepository.findById(currentOrderId).orElse(null);
    }

    private void finish() {
        currentOrderId = null;
        phase = Phase.IDLE;
    }

    private void reset() {
        plc.setCmdBit(false);
        finish();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        log.warn("ECS", paused ? "작업 투입 일시정지 (진행 중 작업은 계속)" : "작업 투입 재개");
    }

    public boolean isPaused() { return paused; }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase.name());
        m.put("paused", paused);
        m.put("currentOrderId", currentOrderId);
        return m;
    }
}
