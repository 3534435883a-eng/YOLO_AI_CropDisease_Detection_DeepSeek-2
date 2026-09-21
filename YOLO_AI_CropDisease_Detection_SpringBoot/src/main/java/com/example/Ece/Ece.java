package com.example.Ece;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.Ece.config.DeepSeekProperties;

@SpringBootApplication
@EnableConfigurationProperties(DeepSeekProperties.class)
public class Ece {

    public static void main(String[] args) {
        SpringApplication.run(Ece.class, args);
    }

}
