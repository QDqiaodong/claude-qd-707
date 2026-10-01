package com.marina.berth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MarinaBerthApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarinaBerthApplication.class, args);
    }
}
