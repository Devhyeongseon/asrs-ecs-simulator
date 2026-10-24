package com.hyeongseon.asrs;

import com.hyeongseon.asrs.domain.LocationStatus;
import com.hyeongseon.asrs.domain.OrderStatus;
import com.hyeongseon.asrs.domain.WorkOrder;
import com.hyeongseon.asrs.ecs.EcsService;
import com.hyeongseon.asrs.plc.VirtualPlc;
import com.hyeongseon.asrs.repository.EventLogRepository;
import com.hyeongseon.asrs.repository.LocationRepository;
import com.hyeongseon.asrs.repository.WorkOrderRepository;
import com.hyeongseon.asrs.service.WmsOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 현장에서 겪은 이슈를 시나리오로 검증하는 통합 테스트.
 * ECS/PLC 스케줄러가 실제로 돌아가는 상태에서 실행된다.
 */
@SpringBootTest
class AsrsScenarioTest {

    @Autowired WmsOrderService wms;
    @Autowired EcsService ecs;
    @Autowired VirtualPlc plc;
    @Autowired WorkOrderRepository orders;
    @Autowired LocationRepository locations;
    @Autowired EventLogRepository events;

    @BeforeEach
    void stopNewDispatch() {
        plc.setFaults(false, false, false);
        ecs.setPaused(true);
        waitUntil(() -> "IDLE".equals(ecs.status().get("phase")), 20000);
    }

    @Test
    void 같은_팔레트에_진행중_작업이_있으면_중복_작업지시를_차단한다() {
        String pallet = idleStockPallet();
        wms.createOutbound(pallet, false);

        assertThrows(ResponseStatusException.class, () -> wms.createOutbound(pallet, false));
    }

    @Test
    void 긴급_작업은_대기열_맨_앞에_들어간다() {
        wms.receiveSampleSchedule(3, false);
        WorkOrder urgent = wms.createInbound(null, null, null, true);

        List<WorkOrder> queue = orders.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING);
        assertEquals(urgent.getId(), queue.get(0).getId());
        assertTrue(queue.get(0).isUrgent());
    }

    @Test
    void 대기_작업을_취소하면_큐에서_빠지고_서열은_연속으로_재정렬된다() {
        wms.receiveSampleSchedule(3, false);
        List<WorkOrder> before = orders.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING);

        wms.cancel(before.get(1).getId());

        List<WorkOrder> after = orders.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING);
        assertEquals(before.size() - 1, after.size());
        for (int i = 1; i < after.size(); i++) {
            assertEquals(after.get(i - 1).getSeq() + 1, after.get(i).getSeq());
        }
        assertEquals(OrderStatus.CANCELED, orders.findById(before.get(1).getId()).orElseThrow().getStatus());
    }

    @Test
    void 완료_신호가_중복으로_들어와도_재고는_한_번만_반영된다() {
        for (WorkOrder w : orders.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING)) {
            wms.cancel(w.getId());
        }
        long stockBefore = locations.countByStatus(LocationStatus.OCCUPIED);

        plc.setFaults(false, false, true);
        WorkOrder order = wms.createInbound(null, null, null, false);
        ecs.setPaused(false);

        waitUntil(() -> orders.findById(order.getId()).orElseThrow().getStatus() == OrderStatus.DONE, 20000);
        waitUntil(() -> events.findTop80ByOrderByIdDesc().stream()
                .anyMatch(e -> e.getMessage().contains("중복 완료 신호")), 10000);
        waitUntil(() -> "IDLE".equals(ecs.status().get("phase")), 10000);

        assertEquals(stockBefore + 1, locations.countByStatus(LocationStatus.OCCUPIED));
    }

    private String idleStockPallet() {
        return locations.findByStatusOrderByRowNoAscColNoAsc(LocationStatus.OCCUPIED).stream()
                .map(l -> l.getPalletNo())
                .filter(p -> !orders.existsByPalletNoAndStatusIn(p, WmsOrderService.ACTIVE))
                .findFirst()
                .orElseThrow();
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        fail("조건을 " + timeoutMs + "ms 안에 만족하지 못함");
    }
}
