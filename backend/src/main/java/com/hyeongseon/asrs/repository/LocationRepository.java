package com.hyeongseon.asrs.repository;

import com.hyeongseon.asrs.domain.Location;
import com.hyeongseon.asrs.domain.LocationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, String> {
    Optional<Location> findByRowNoAndColNo(int rowNo, int colNo);
    Optional<Location> findByPalletNo(String palletNo);
    List<Location> findAllByOrderByRowNoAscColNoAsc();
    List<Location> findByStatusOrderByRowNoAscColNoAsc(LocationStatus status);
    long countByStatus(LocationStatus status);
}
