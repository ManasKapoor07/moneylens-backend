package com.moneylens;

import com.moneylens.config.DotenvInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
public class MoneyLensApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MoneyLensApplication.class);
        app.addInitializers(new DotenvInitializer());
        app.run(args);
    }
}