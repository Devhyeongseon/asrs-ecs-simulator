package com.hyeongseon.asrs.api;

import com.hyeongseon.asrs.domain.LocationStatus;
import com.hyeongseon.asrs.domain.OrderStatus;
import com.hyeongseon.asrs.domain.WorkOrder;
import com.hyeongseon.asrs.ecs.EcsService;
import com.hyeongseon.asrs.plc.VirtualPlc;
import com.hyeongseon.asrs.repository.LocationRepository;
import com.hyeongseon.asrs.repository.WorkOrderRepository;
import com.hyeongseon.asrs.service.EventLogService;
import com.hyeongseon.asrs.service.WmsOrderService;
import com.hyeongseon.asrs.service.WmsOrderService.ScheduleItem;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AsrsController {

    public record InboundRequest(String palletNo, String itemName, String lotId, Boolean urgent) { }
    public record OutboundRequest(String palletNo, Boolean urgent) { }
    public record ScheduleRequest(String mode, List<ScheduleItem> items) { }
    public record FaultRequest(Boolean sensor, Boolean noResponse, Boolean duplicateDone) { }
    public record PauseRequest(boolean paused) { }

    private final WmsOrderService wms;
    private final EcsService ecs;
    private final VirtualPlc plc;
    private final LocationRepository locationRepository;
    private final WorkOrderRepository orderRepository;
    private final EventLogService log;

    public AsrsController(WmsOrderService wms, EcsService ecs, VirtualPlc plc,
                          LocationRepository locationRepository, WorkOrderRepository orderRepository,
                          EventLogService log) {
        this.wms = wms;
        this.ecs = ecs;
        this.plc = plc;
        this.locationRepository = locationRepository;
        this.orderRepository = orderRepository;
        this.log = log;
    }

    /** 대시보드가 주기적으로 호출하는 전체 상태 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stock", locationRepository.countByStatus(LocationStatus.OCCUPIED));
        stats.put("capacity", locationRepository.count());
        stats.put("waiting", orderRepository.countByStatus(OrderStatus.WAITING));
        stats.put("done", orderRepository.countByStatus(OrderStatus.DONE));
        stats.put("error", orderRepository.countByStatus(OrderStatus.ERROR));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stats", stats);
        m.put("ecs", ecs.status());
        m.put("plc", plc.snapshot());
        m.put("faults", plc.getFaults());
        m.put("locations", locationRepository.findAllByOrderByRowNoAscColNoAsc());
        m.put("queue", orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING));
        m.put("orders", orderRepository.findTop40ByOrderByIdDesc());
        m.put("events", log.recent());
        return m;
    }

    // ----- 단건 / 긴급 작업 -----

    @PostMapping("/orders/inbound")
    public WorkOrder inbound(@RequestBody(required = false) InboundRequest req) {
        InboundRequest r = req == null ? new InboundRequest(null, null, null, false) : req;
        return wms.createInbound(r.palletNo(), r.itemName(), r.lotId(), Boolean.TRUE.equals(r.urgent()));
    }

    @PostMapping("/orders/outbound")
    public WorkOrder outbound(@RequestBody OutboundRequest req) {
        return wms.createOutbound(req.palletNo(), Boolean.TRUE.equals(req.urgent()));
    }

    // ----- MES 스케줄 -----

    @PostMapping("/mes/schedule")
    public Map<String, Object> schedule(@RequestBody ScheduleRequest req) {
        return wms.receiveSchedule(req.items(), "replace".equalsIgnoreCase(req.mode()));
    }

    @PostMapping("/mes/schedule/sample")
    public Map<String, Object> sampleSchedule(@RequestParam(defaultValue = "10") int count,
                                              @RequestParam(defaultValue = "append") String mode) {
        return wms.receiveSampleSchedule(Math.min(count, 30), "replace".equalsIgnoreCase(mode));
    }

    // ----- 대기열 관리 -----

    @PostMapping("/orders/{id}/move")
    public Map<String, String> move(@PathVariable Long id, @RequestParam String dir) {
        wms.move(id, dir);
        return Map.of("result", "ok");
    }

    @DeleteMapping("/orders/{id}")
    public Map<String, String> cancel(@PathVariable Long id) {
        wms.cancel(id);
        return Map.of("result", "ok");
    }

    @PostMapping("/orders/{id}/retry")
    public WorkOrder retry(@PathVariable Long id) {
        return wms.retry(id);
    }

    // ----- ECS / 시뮬레이션 -----

    @PostMapping("/ecs/pause")
    public Map<String, Object> pause(@RequestBody PauseRequest req) {
        ecs.setPaused(req.paused());
        return ecs.status();
    }

    @PostMapping("/faults")
    public Map<String, Boolean> faults(@RequestBody FaultRequest req) {
        plc.setFaults(req.sensor(), req.noResponse(), req.duplicateDone());
        log.warn("SIM", "장애 주입 설정 변경: " + plc.getFaults());
        return plc.getFaults();
    }
}
