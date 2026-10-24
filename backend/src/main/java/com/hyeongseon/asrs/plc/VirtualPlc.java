package com.hyeongseon.asrs.plc;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스태커크레인 PLC 시뮬레이터.
 *
 * 실제 현장에서는 LS ELECTRIC EdgeHub에 PLC의 IP/Port/기종을 등록하고 드라이버로 메모리를 읽고 쓴다.
 * 여기서는 같은 메모리맵 구조를 Java 안에서 흉내 낸다.
 *
 *  ECS -> PLC (ECS가 쓰는 영역)          PLC -> ECS (PLC가 쓰는 영역)
 *  D100 작업요청 비트                     D200 수신확인(ACK) 비트
 *  D101 작업유형 (1=입고, 2=출고)          D201 동작중(BUSY) 비트
 *  D102 목표 행                           D202 작업완료(DONE) 비트
 *  D103 목표 열                           D203 이상(ERR) 비트
 *  D104 작업 ID                           D204 에러코드
 *  D105 완료확인(DONE_ACK) 비트            D205 작업 ID 에코
 *  D106 에러리셋 비트                      D206/D207 크레인 현재 행/열
 */
@Component
public class VirtualPlc {

    public enum Phase { IDLE, ACKED, MOVING, DONE_WAIT, ERROR }

    public static final int ERR_SENSOR_NOT_DETECTED = 101;

    // ----- ECS -> PLC -----
    private boolean cmdBit;
    private int cmdType;
    private int cmdRow;
    private int cmdCol;
    private int cmdJobId;
    private boolean doneAckBit;
    private boolean errResetBit;

    // ----- PLC -> ECS -----
    private boolean ackBit;
    private boolean busyBit;
    private boolean doneBit;
    private boolean errBit;
    private int errCode;
    private int echoJobId;
    private int curRow = 1;
    private int curCol = 0;   // 0열 = 입출고 포트

    // ----- 내부 상태 -----
    private Phase phase = Phase.IDLE;
    private int jobType, jobRow, jobCol, jobId;
    private long nextStepAt;

    // 중복 완료 신호 재현용
    private boolean ghostPending;
    private boolean ghostActive;
    private int ghostJobId;

    // ----- 장애 주입 스위치 -----
    private boolean faultSensor;      // 입고 시 화물 미감지
    private boolean faultNoResponse;  // 작업요청 무시 (통신/PLC 무응답)
    private boolean faultDuplicateDone; // 완료 신호를 한 번 더 보냄

    @Scheduled(fixedRate = 200)
    public synchronized void scan() {
        long now = System.currentTimeMillis();

        // 중복 완료 신호 (장애 재현): ECS가 완료확인을 내리자마자 같은 작업 ID로 DONE을 한 번 더 올린다
        if (ghostPending && !doneAckBit) {
            ghostPending = false;
            ghostActive = true;
            echoJobId = ghostJobId;
            doneBit = true;
            return;
        }
        if (ghostActive) {
            if (doneAckBit) {
                doneBit = false;
                ghostActive = false;
                echoJobId = 0;
            }
            return; // 정리될 때까지 새 작업을 받지 않음
        }

        switch (phase) {
            case IDLE -> {
                if (cmdBit && !faultNoResponse) {
                    jobType = cmdType;
                    jobRow = cmdRow;
                    jobCol = cmdCol;
                    jobId = cmdJobId;
                    echoJobId = jobId;
                    ackBit = true;
                    phase = Phase.ACKED;
                }
            }
            case ACKED -> {
                // ECS가 요청 비트를 내려야 다음 단계로 (핸드셰이크)
                if (!cmdBit) {
                    ackBit = false;
                    if (faultSensor && jobType == 1) {
                        errBit = true;
                        errCode = ERR_SENSOR_NOT_DETECTED;
                        phase = Phase.ERROR;
                    } else {
                        busyBit = true;
                        nextStepAt = now + 300;
                        phase = Phase.MOVING;
                    }
                }
            }
            case MOVING -> {
                if (now >= nextStepAt) {
                    if (curCol != jobCol) {
                        curCol += Integer.signum(jobCol - curCol);
                    } else if (curRow != jobRow) {
                        curRow += Integer.signum(jobRow - curRow);
                    } else {
                        busyBit = false;
                        doneBit = true;
                        phase = Phase.DONE_WAIT;
                    }
                    nextStepAt = now + 250;
                }
            }
            case DONE_WAIT -> {
                if (doneAckBit) {
                    doneBit = false;
                    if (faultDuplicateDone) {
                        ghostJobId = jobId;
                        ghostPending = true;
                    } else {
                        echoJobId = 0;
                    }
                    phase = Phase.IDLE;
                }
            }
            case ERROR -> {
                if (errResetBit) {
                    errBit = false;
                    errCode = 0;
                    echoJobId = 0;
                    busyBit = false;
                    phase = Phase.IDLE;
                }
            }
        }
    }

    // ===== ECS가 쓰는 영역 =====
    public synchronized void writeCommand(int type, int row, int col, int jobId) {
        this.cmdType = type;
        this.cmdRow = row;
        this.cmdCol = col;
        this.cmdJobId = jobId;
    }
    public synchronized void setCmdBit(boolean v) { this.cmdBit = v; }
    public synchronized void setDoneAckBit(boolean v) { this.doneAckBit = v; }
    public synchronized void setErrResetBit(boolean v) { this.errResetBit = v; }

    // ===== ECS가 읽는 영역 =====
    public synchronized boolean isAck() { return ackBit; }
    public synchronized boolean isDone() { return doneBit; }
    public synchronized boolean isErr() { return errBit; }
    public synchronized int getErrCode() { return errCode; }
    public synchronized int getEchoJobId() { return echoJobId; }

    // ===== 장애 주입 =====
    public synchronized void setFaults(Boolean sensor, Boolean noResponse, Boolean duplicateDone) {
        if (sensor != null) faultSensor = sensor;
        if (noResponse != null) faultNoResponse = noResponse;
        if (duplicateDone != null) faultDuplicateDone = duplicateDone;
    }

    public synchronized Map<String, Boolean> getFaults() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("sensor", faultSensor);
        m.put("noResponse", faultNoResponse);
        m.put("duplicateDone", faultDuplicateDone);
        return m;
    }

    /** 화면 표시용 메모리맵 스냅샷 */
    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> ecsToPlc = new ArrayList<>();
        ecsToPlc.add(word("D100", "작업요청", cmdBit ? 1 : 0, true));
        ecsToPlc.add(word("D101", "작업유형", cmdType, false));
        ecsToPlc.add(word("D102", "목표 행", cmdRow, false));
        ecsToPlc.add(word("D103", "목표 열", cmdCol, false));
        ecsToPlc.add(word("D104", "작업 ID", cmdJobId, false));
        ecsToPlc.add(word("D105", "완료확인", doneAckBit ? 1 : 0, true));
        ecsToPlc.add(word("D106", "에러리셋", errResetBit ? 1 : 0, true));

        List<Map<String, Object>> plcToEcs = new ArrayList<>();
        plcToEcs.add(word("D200", "수신확인", ackBit ? 1 : 0, true));
        plcToEcs.add(word("D201", "동작중", busyBit ? 1 : 0, true));
        plcToEcs.add(word("D202", "작업완료", doneBit ? 1 : 0, true));
        plcToEcs.add(word("D203", "이상", errBit ? 1 : 0, true));
        plcToEcs.add(word("D204", "에러코드", errCode, false));
        plcToEcs.add(word("D205", "작업 ID 에코", echoJobId, false));
        plcToEcs.add(word("D206", "크레인 행", curRow, false));
        plcToEcs.add(word("D207", "크레인 열", curCol, false));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase.name());
        m.put("craneRow", curRow);
        m.put("craneCol", curCol);
        m.put("ecsToPlc", ecsToPlc);
        m.put("plcToEcs", plcToEcs);
        return m;
    }

    private static Map<String, Object> word(String addr, String name, int value, boolean bit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("addr", addr);
        m.put("name", name);
        m.put("value", value);
        m.put("bit", bit);
        return m;
    }
}
