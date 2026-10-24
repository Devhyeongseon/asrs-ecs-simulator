package com.hyeongseon.asrs.repository;

import com.hyeongseon.asrs.domain.OrderStatus;
import com.hyeongseon.asrs.domain.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    Optional<WorkOrder> findFirstByStatusOrderBySeqAscIdAsc(OrderStatus status);
    List<WorkOrder> findTop40ByOrderByIdDesc();
    List<WorkOrder> findByStatusIn(Collection<OrderStatus> statuses);
    boolean existsByPalletNoAndStatusIn(String palletNo, Collection<OrderStatus> statuses);
    long countByStatus(OrderStatus status);
    List<WorkOrder> findByStatusOrderBySeqAscIdAsc(OrderStatus status);

    @Query("select coalesce(max(w.seq), 0) from WorkOrder w where w.status <> :status")
    int findMaxSeqExcept(@Param("status") OrderStatus status);

    @Query("select coalesce(max(w.seq), 0) from WorkOrder w")
    int findMaxSeq();
}
