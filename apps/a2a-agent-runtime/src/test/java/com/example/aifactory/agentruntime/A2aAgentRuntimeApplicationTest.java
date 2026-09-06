package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import com.example.aifactory.agentcore.AgentContractValidator;
import com.example.aifactory.agentcore.AgentManifest;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.PromptRepository;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
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
        RoleScopedAgentContext scope = context.getBean(RoleScopedAgentContext.class);
        assertEquals("developer", scope.identity().role());
        assertEquals(java.util.Set.of("code-task-v1"), scope.acceptedInputContracts());
        assertEquals(java.util.Set.of("patch-proposal-v1"), scope.producedOutputContracts());
        assertThrows(SecurityException.class, () -> scope.requireActiveRole("patch-repair"));
        assertThrows(SecurityException.class, () -> scope.requireTool("scm.create_commit"));
        assertNotNull(scope.systemPrompt());
        assertNotNull(context.getBean(LlmCompletionPort.class));
        assertNotNull(context.getBean(McpToolPort.class));
        assertEquals("developer", context.getBean(AgentManifest.class).role());
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(AgentCatalog.class));
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(PromptRepository.class));
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(AgentContractValidator.class));
    }

    @Test
    void doesNotLoadTheOrchestratorDatasource() {
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(DataSource.class));
    }
}
