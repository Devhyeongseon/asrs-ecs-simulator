package com.hyeongseon.asrs.domain;

/** 작업 유형. PLC에는 코드값으로 전달된다 (입고=1, 출고=2). */
public enum OrderType {
    INBOUND(1), OUTBOUND(2);

    private final int plcCode;

    OrderType(int plcCode) { this.plcCode = plcCode; }

    public int getPlcCode() { return plcCode; }
}
