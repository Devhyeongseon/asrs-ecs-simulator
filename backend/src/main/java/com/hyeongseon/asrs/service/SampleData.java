package com.hyeongseon.asrs.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

/** 가짜 데이터 생성기 (실제 고객사 데이터 아님). EV 차체 부품 팔레트를 가정. */
public final class SampleData {

    private static final List<String> ITEMS = List.of(
            "프론트 사이드멤버 LH", "프론트 사이드멤버 RH", "대시 패널 ASSY", "센터 플로어 패널",
            "리어 플로어 패널", "루프 레일 LH", "루프 레일 RH", "센터 필러 LH", "센터 필러 RH",
            "배터리 마운팅 브래킷", "카울 크로스 멤버", "리어 휠하우스 ASSY");

    private static final Random RANDOM = new Random(7);
    private static int palletSeq = 1000;

    private SampleData() { }

    public static synchronized String nextPalletNo() {
        palletSeq++;
        return "PLT-" + palletSeq;
    }

    public static synchronized String randomItem() {
        return ITEMS.get(RANDOM.nextInt(ITEMS.size()));
    }

    public static synchronized String randomLot() {
        String day = LocalDate.now().minusDays(RANDOM.nextInt(5)).format(DateTimeFormatter.ofPattern("yyMMdd"));
        return "L" + day + "-" + String.format("%03d", RANDOM.nextInt(1000)) + (char) ('A' + RANDOM.nextInt(3));
    }

    public static synchronized int nextInt(int bound) {
        return RANDOM.nextInt(bound);
    }
}
