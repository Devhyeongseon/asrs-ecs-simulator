package com.hyeongseon.asrs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AsrsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AsrsApplication.class, args);
    }
}
