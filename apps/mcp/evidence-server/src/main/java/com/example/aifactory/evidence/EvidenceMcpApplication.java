package com.example.aifactory.evidence;

import com.example.aifactory.evidence.config.EvidenceProperties;
import com.example.aifactory.evidence.config.McpRoleSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({EvidenceProperties.class, McpRoleSecurityProperties.class})
public class EvidenceMcpApplication {
    public static void main(String[] args) { SpringApplication.run(EvidenceMcpApplication.class, args); }
}
