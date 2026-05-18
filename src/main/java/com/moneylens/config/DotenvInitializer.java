package com.moneylens.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class DotenvInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        Map<String, Object> props = new HashMap<>();
        dotenv.entries().forEach(e -> props.put(e.getKey(), e.getValue()));
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("dotenvProperties", props));
    }
}