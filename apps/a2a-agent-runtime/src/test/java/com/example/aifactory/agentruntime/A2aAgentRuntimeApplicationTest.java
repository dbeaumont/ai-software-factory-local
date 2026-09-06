package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import com.example.aifactory.agentcore.AgentContractValidator;
import com.example.aifactory.agentcore.AgentManifest;
import com.example.aifactory.agentcore.PromptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=agent-runtime",
        "ai-factory.agent-runtime.role=developer"
})
class A2aAgentRuntimeApplicationTest {
    @Autowired ApplicationContext context;
    @Autowired AgentRuntimeProperties properties;

    @Test
    void startsAsGenericRuntimeWithTheSharedCore() {
        assertEquals("developer", properties.role());
        assertNotNull(context.getBean(AgentCatalog.class));
        assertNotNull(context.getBean(PromptRepository.class));
        assertNotNull(context.getBean(AgentContractValidator.class));
        assertEquals("developer", context.getBean(AgentManifest.class).role());
    }

    @Test
    void doesNotLoadTheOrchestratorDatasource() {
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(DataSource.class));
    }
}
