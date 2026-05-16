package com.moneylens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MoneyLensApplication {
    public static void main(String[] args) {
        SpringApplication.run(MoneyLensApplication.class, args);
    }
}
