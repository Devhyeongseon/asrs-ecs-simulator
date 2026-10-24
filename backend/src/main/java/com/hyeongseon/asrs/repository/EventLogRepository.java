package com.hyeongseon.asrs.repository;

import com.hyeongseon.asrs.domain.EventLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {
    List<EventLog> findTop80ByOrderByIdDesc();
}
