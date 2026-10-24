package com.hyeongseon.asrs.service;

import com.hyeongseon.asrs.domain.EventLog;
import com.hyeongseon.asrs.repository.EventLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventLogService {

    private final EventLogRepository repository;

    public EventLogService(EventLogRepository repository) {
        this.repository = repository;
    }

    public void info(String source, String message) { repository.save(new EventLog("INFO", source, message)); }
    public void warn(String source, String message) { repository.save(new EventLog("WARN", source, message)); }
    public void error(String source, String message) { repository.save(new EventLog("ERROR", source, message)); }

    public List<EventLog> recent() { return repository.findTop80ByOrderByIdDesc(); }
}
