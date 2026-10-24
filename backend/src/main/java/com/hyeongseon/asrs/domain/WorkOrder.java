package com.hyeongseon.asrs.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** WMS 작업목록 1건. MES 서열·작업지시를 받아 WMS가 생성하고, ECS가 읽어서 설비에 내린다. */
@Entity
@Table(name = "work_order")
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderNo;     // WO-261025-0001

    @Enumerated(EnumType.STRING)
    private OrderType type;

    private int seq;            // 서열 (작은 값부터 처리)
    private String palletNo;
    private String itemName;
    private String lotId;
    private int targetRow;
    private int targetCol;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.WAITING;

    private boolean urgent;     // 긴급 삽입 여부
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected WorkOrder() { }

    public WorkOrder(String orderNo, OrderType type, int seq, String palletNo, String itemName,
                     String lotId, int targetRow, int targetCol) {
        this.orderNo = orderNo;
        this.type = type;
        this.seq = seq;
        this.palletNo = palletNo;
        this.itemName = itemName;
        this.lotId = lotId;
        this.targetRow = targetRow;
        this.targetCol = targetCol;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void changeStatus(OrderStatus status, String message) {
        this.status = status;
        this.message = message;
        this.updatedAt = LocalDateTime.now();
    }

    public void changeSeq(int seq) {
        this.seq = seq;
        this.updatedAt = LocalDateTime.now();
    }

    public void markUrgent() { this.urgent = true; }

    public String getLocationId() { return String.format("R%02d-C%02d", targetRow, targetCol); }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public OrderType getType() { return type; }
    public int getSeq() { return seq; }
    public String getPalletNo() { return palletNo; }
    public String getItemName() { return itemName; }
    public String getLotId() { return lotId; }
    public int getTargetRow() { return targetRow; }
    public int getTargetCol() { return targetCol; }
    public OrderStatus getStatus() { return status; }
    public boolean isUrgent() { return urgent; }
    public String getMessage() { return message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
