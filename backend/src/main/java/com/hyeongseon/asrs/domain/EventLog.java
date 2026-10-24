package com.hyeongseon.asrs.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "event_log")
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "logged_at")
    private LocalDateTime time;
    private String level;   // INFO / WARN / ERROR
    private String source;  // WMS / ECS / PLC
    @Column(length = 500)
    private String message;

    protected EventLog() { }

    public EventLog(String level, String source, String message) {
        this.time = LocalDateTime.now();
        this.level = level;
        this.source = source;
        this.message = message;
    }

    public Long getId() { return id; }
    public LocalDateTime getTime() { return time; }
    public String getLevel() { return level; }
    public String getSource() { return source; }
    public String getMessage() { return message; }
}
