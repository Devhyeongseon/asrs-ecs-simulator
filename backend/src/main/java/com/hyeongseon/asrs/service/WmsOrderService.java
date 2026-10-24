package com.hyeongseon.asrs.service;

import com.hyeongseon.asrs.domain.*;
import com.hyeongseon.asrs.repository.LocationRepository;
import com.hyeongseon.asrs.repository.WorkOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WMS 역할.
 * - MES 작업 스케줄 수신(추가 / 전체 교체), 단건·긴급 작업 생성
 * - 대기열 서열 관리(순서 변경, 취소, 서열 재정렬)
 * - 같은 팔레트에 진행 중인 작업이 있으면 중복 작업지시로 차단
 *
 * 서열 규칙: 대기(WAITING) 작업은 항상 "이미 진행·완료된 작업의 최대 서열 + 1" 부터 순서대로 번호를 갖는다.
 * ECS는 대기 작업 중 서열이 가장 작은 것부터 가져간다.
 */
@Service
public class WmsOrderService {

    public static final EnumSet<OrderStatus> ACTIVE =
            EnumSet.of(OrderStatus.WAITING, OrderStatus.REQUESTED, OrderStatus.RUNNING);

    public record ScheduleItem(String type, String palletNo, String itemName, String lotId) { }

    private final WorkOrderRepository orderRepository;
    private final LocationRepository locationRepository;
    private final EventLogService log;

    public WmsOrderService(WorkOrderRepository orderRepository, LocationRepository locationRepository,
                           EventLogService log) {
        this.orderRepository = orderRepository;
        this.locationRepository = locationRepository;
        this.log = log;
    }

    // ================= 작업 생성 =================

    @Transactional
    public WorkOrder createInbound(String palletNo, String itemName, String lotId, boolean urgent) {
        String pallet = isBlank(palletNo) ? SampleData.nextPalletNo() : palletNo.trim();
        if (locationRepository.findByPalletNo(pallet).isPresent()
                || orderRepository.existsByPalletNoAndStatusIn(pallet, ACTIVE)) {
            log.warn("WMS", "중복 작업지시 차단: " + pallet + " 는 이미 재고 또는 진행 중 작업이 있음");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "중복 작업지시: " + pallet);
        }

        Set<String> reserved = orderRepository.findByStatusIn(ACTIVE).stream()
                .map(WorkOrder::getLocationId).collect(Collectors.toSet());
        Location target = locationRepository.findByStatusOrderByRowNoAscColNoAsc(LocationStatus.EMPTY).stream()
                .filter(l -> !reserved.contains(l.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "빈 랙이 없습니다"));

        WorkOrder order = new WorkOrder(nextOrderNo(), OrderType.INBOUND, 0, pallet,
                isBlank(itemName) ? SampleData.randomItem() : itemName.trim(),
                isBlank(lotId) ? SampleData.randomLot() : lotId.trim(),
                target.getRowNo(), target.getColNo());
        enqueue(order, urgent);
        log.info("WMS", (urgent ? "[긴급] " : "") + "입고 작업 생성 " + order.getOrderNo()
                + " / " + pallet + " → " + target.getId() + " (서열 " + order.getSeq() + ")");
        return order;
    }

    @Transactional
    public WorkOrder createOutbound(String palletNo, boolean urgent) {
        if (isBlank(palletNo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "출고할 팔레트 번호가 필요합니다");
        }
        Location loc = locationRepository.findByPalletNo(palletNo.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "재고에 없는 팔레트: " + palletNo));
        if (orderRepository.existsByPalletNoAndStatusIn(loc.getPalletNo(), ACTIVE)) {
            log.warn("WMS", "중복 작업지시 차단: " + loc.getPalletNo() + " 에 진행 중인 작업이 있음");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "중복 작업지시: " + loc.getPalletNo());
        }
        WorkOrder order = new WorkOrder(nextOrderNo(), OrderType.OUTBOUND, 0,
                loc.getPalletNo(), loc.getItemName(), loc.getLotId(), loc.getRowNo(), loc.getColNo());
        enqueue(order, urgent);
        log.info("WMS", (urgent ? "[긴급] " : "") + "출고 작업 생성 " + order.getOrderNo()
                + " / " + loc.getPalletNo() + " ← " + loc.getId() + " (서열 " + order.getSeq() + ")");
        return order;
    }

    // ================= MES 스케줄 수신 =================

    /**
     * MES 작업 스케줄 일괄 수신.
     * replace=true 이면 기존 대기 작업을 모두 취소하고 새 스케줄로 교체한다 (진행 중 작업은 유지).
     * 행 단위로 검증하고, 실패한 행은 사유와 함께 돌려준다.
     */
    @Transactional
    public Map<String, Object> receiveSchedule(List<ScheduleItem> items, boolean replace) {
        int canceled = 0;
        if (replace) {
            for (WorkOrder w : orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING)) {
                w.changeStatus(OrderStatus.CANCELED, "MES 스케줄 재수신으로 취소");
                canceled++;
            }
            log.warn("MES", "스케줄 전체 교체: 기존 대기 작업 " + canceled + "건 취소");
        }

        int accepted = 0;
        List<Map<String, Object>> rejected = new ArrayList<>();
        List<ScheduleItem> list = items == null ? List.of() : items;
        for (int i = 0; i < list.size(); i++) {
            ScheduleItem it = list.get(i);
            try {
                String type = it.type() == null ? "" : it.type().trim().toUpperCase();
                switch (type) {
                    case "IN", "INBOUND", "입고" -> createInbound(it.palletNo(), it.itemName(), it.lotId(), false);
                    case "OUT", "OUTBOUND", "출고" -> createOutbound(it.palletNo(), false);
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업유형 오류: " + it.type());
                }
                accepted++;
            } catch (ResponseStatusException e) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("line", i + 1);
                r.put("palletNo", it.palletNo());
                r.put("reason", e.getReason() != null ? e.getReason() : e.getMessage());
                rejected.add(r);
            }
        }
        log.info("MES", "작업 스케줄 수신 " + list.size() + "건 → 등록 " + accepted + "건, 거부 " + rejected.size() + "건");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", list.size());
        result.put("accepted", accepted);
        result.put("canceled", canceled);
        result.put("rejected", rejected);
        return result;
    }

    /** 데모용 샘플 스케줄: 입고/출고를 섞어서 생성 */
    @Transactional
    public Map<String, Object> receiveSampleSchedule(int count, boolean replace) {
        Set<String> busy = orderRepository.findByStatusIn(ACTIVE).stream()
                .map(WorkOrder::getPalletNo).collect(Collectors.toSet());
        List<String> stock = new ArrayList<>(locationRepository.findByStatusOrderByRowNoAscColNoAsc(LocationStatus.OCCUPIED)
                .stream().map(Location::getPalletNo)
                .filter(p -> replace || !busy.contains(p))
                .toList());
        List<ScheduleItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (SampleData.nextInt(2) == 0 && !stock.isEmpty()) {
                String p = stock.remove(SampleData.nextInt(stock.size()));
                items.add(new ScheduleItem("OUT", p, null, null));
            } else {
                items.add(new ScheduleItem("IN", null, null, null));
            }
        }
        return receiveSchedule(items, replace);
    }

    /** 데모용: 입고/출고를 섞어서 몇 건 바로 생성 */
    @Transactional
    public int createRandom(int count) {
        Map<String, Object> r = receiveSampleSchedule(count, false);
        return (int) r.get("accepted");
    }

    // ================= 대기열 관리 =================

    /** 대기 작업 순서 변경 (up: 한 칸 앞으로, down: 한 칸 뒤로) */
    @Transactional
    public void move(Long id, String direction) {
        List<WorkOrder> queue = new ArrayList<>(orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING));
        int idx = indexOf(queue, id);
        int to = "up".equalsIgnoreCase(direction) ? idx - 1 : idx + 1;
        if (to < 0 || to >= queue.size()) return;
        Collections.swap(queue, idx, to);
        renumber(queue);
        log.info("WMS", queue.get(to).getOrderNo() + " 서열 변경 → " + queue.get(to).getSeq());
    }

    /** 대기 작업 취소. 이미 설비에 내려간 작업은 취소할 수 없다. */
    @Transactional
    public void cancel(Long id) {
        WorkOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "작업 없음: " + id));
        if (order.getStatus() != OrderStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "대기 상태인 작업만 취소할 수 있습니다 (현재 " + order.getStatus() + ")");
        }
        order.changeStatus(OrderStatus.CANCELED, "작업 취소");
        renumber(orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING));
        log.warn("WMS", order.getOrderNo() + " 작업 취소, 대기열 서열 재정렬");
    }

    @Transactional
    public WorkOrder retry(Long id) {
        WorkOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "작업 없음: " + id));
        if (order.getStatus() != OrderStatus.ERROR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "에러 상태인 작업만 재시도할 수 있습니다");
        }
        order.changeStatus(OrderStatus.WAITING, "재시도 요청");
        List<WorkOrder> queue = new ArrayList<>(orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING));
        queue.remove(order);
        queue.add(order);
        renumber(queue);
        log.info("WMS", order.getOrderNo() + " 재시도 → 대기열 맨 뒤 (서열 " + order.getSeq() + ")");
        return order;
    }

    // ================= 내부 =================

    private void enqueue(WorkOrder order, boolean urgent) {
        if (urgent) {
            order.markUrgent();
            orderRepository.save(order);
            List<WorkOrder> queue = new ArrayList<>(orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING));
            queue.remove(order);
            queue.add(0, order);
            renumber(queue);
        } else {
            List<WorkOrder> queue = orderRepository.findByStatusOrderBySeqAscIdAsc(OrderStatus.WAITING);
            int base = queue.isEmpty()
                    ? orderRepository.findMaxSeqExcept(OrderStatus.WAITING) + 1
                    : queue.get(queue.size() - 1).getSeq() + 1;
            order.changeSeq(base);
            orderRepository.save(order);
        }
    }

    /** 대기열을 주어진 순서대로 연속 서열로 다시 매긴다 */
    private void renumber(List<WorkOrder> queue) {
        int seq = orderRepository.findMaxSeqExcept(OrderStatus.WAITING) + 1;
        for (WorkOrder w : queue) {
            w.changeSeq(seq++);
        }
    }

    private static int indexOf(List<WorkOrder> queue, Long id) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getId().equals(id)) return i;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "대기 상태인 작업만 순서를 바꿀 수 있습니다");
    }

    private String nextOrderNo() {
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        return String.format("WO-%s-%04d", day, orderRepository.count() + 1);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
