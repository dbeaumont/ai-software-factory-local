package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import com.example.aifactory.agentcore.AgentContractValidator;
import com.example.aifactory.agentcore.AgentManifest;
import com.example.aifactory.agentcore.PromptRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Runtime-only adapters around the framework-neutral agent core. */
@Configuration(proxyBeanMethods = false)
class AgentCoreConfiguration {
    @Bean AgentCatalog agentCatalog() { return new AgentCatalog(); }
    @Bean PromptRepository promptRepository() { return new PromptRepository(); }
    @Bean AgentManifest agentManifest(AgentRuntimeProperties properties, AgentCatalog catalog,
                                      PromptRepository prompts) {
        return AgentManifest.load(properties.role(), catalog, prompts);
    }
    @Bean AgentContractValidator agentContractValidator(ObjectMapper mapper, AgentCatalog catalog) {
        return new AgentContractValidator(mapper, catalog);
    }
}
