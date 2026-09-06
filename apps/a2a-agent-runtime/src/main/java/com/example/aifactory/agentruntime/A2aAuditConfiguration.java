package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.A2aDecisionJournal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class A2aAuditConfiguration {
    @Bean
    A2aDecisionJournal a2aDecisionJournal() {
        return new A2aDecisionJournal();
    }
}
