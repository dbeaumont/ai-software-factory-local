package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentManifest;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Runtime-only adapters around the framework-neutral agent core. */
@Configuration(proxyBeanMethods = false)
class AgentCoreConfiguration {
    @Bean RoleScopedAgentContext roleScopedAgentContext(AgentRuntimeProperties properties, ObjectMapper mapper) {
        return RoleScopedAgentContext.load(properties.role(), mapper);
    }

    @Bean AgentManifest agentManifest(RoleScopedAgentContext context) {
        return context.identity();
    }
}
