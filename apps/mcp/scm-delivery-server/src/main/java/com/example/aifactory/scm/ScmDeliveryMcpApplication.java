package com.example.aifactory.scm;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ScmDeliveryProperties.class)
public class ScmDeliveryMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScmDeliveryMcpApplication.class, args);
    }
}
