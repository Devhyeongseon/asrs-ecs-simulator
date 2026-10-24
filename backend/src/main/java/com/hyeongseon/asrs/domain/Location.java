package com.hyeongseon.asrs.domain;

import jakarta.persistence.*;

/** 랙 셀 1칸 (행 x 열). WMS 재고의 최소 단위. */
@Entity
@Table(name = "rack_location")
public class Location {

    @Id
    private String id;          // "R02-C07"

    private int rowNo;
    private int colNo;

    @Enumerated(EnumType.STRING)
    private LocationStatus status = LocationStatus.EMPTY;

    private String palletNo;
    private String itemName;
    private String lotId;

    protected Location() { }

    public Location(int rowNo, int colNo) {
        this.rowNo = rowNo;
        this.colNo = colNo;
        this.id = String.format("R%02d-C%02d", rowNo, colNo);
    }

    public void store(String palletNo, String itemName, String lotId) {
        this.status = LocationStatus.OCCUPIED;
        this.palletNo = palletNo;
        this.itemName = itemName;
        this.lotId = lotId;
    }

    public void clear() {
        this.status = LocationStatus.EMPTY;
        this.palletNo = null;
        this.itemName = null;
        this.lotId = null;
    }

    public boolean isEmpty() { return status == LocationStatus.EMPTY; }

    public String getId() { return id; }
    public int getRowNo() { return rowNo; }
    public int getColNo() { return colNo; }
    public LocationStatus getStatus() { return status; }
    public String getPalletNo() { return palletNo; }
    public String getItemName() { return itemName; }
    public String getLotId() { return lotId; }
}
