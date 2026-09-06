package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentManifest;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/** Runtime-only adapters around the framework-neutral agent core. */
@Configuration(proxyBeanMethods = false)
class AgentCoreConfiguration {
    @Bean WebClient.Builder agentWebClientBuilder() { return WebClient.builder(); }

    @Bean RoleScopedAgentContext roleScopedAgentContext(AgentRuntimeProperties properties, ObjectMapper mapper) {
        return RoleScopedAgentContext.load(properties.role(), mapper);
    }

    @Bean AgentManifest agentManifest(RoleScopedAgentContext context) {
        return context.identity();
    }

    @Bean LlmCompletionPort llmCompletionPort(WebClient.Builder builder, ObjectMapper mapper,
                                             LlmAdapterProperties properties) {
        return new OpenAiCompatibleLlmAdapter(builder, mapper, properties);
    }

    @Bean McpToolPort mcpToolPort(WebClient.Builder builder, ObjectMapper mapper,
                                 RoleScopedAgentContext role, AgentMcpProperties properties) {
        return new RoleScopedMcpClient(role, properties, new McpSdkSessionFactory(builder, mapper, role, properties));
    }

    @Bean AgentExecutionWorker agentExecutionWorker(RoleScopedAgentContext role, LlmCompletionPort llm,
                                                     McpToolPort mcp) {
        return new AgentExecutionWorker(role, llm, mcp);
    }

    @Bean EvidenceArtifactPublisher evidenceArtifactPublisher(
            RoleScopedAgentContext role, A2aTaskStore store, AgentMcpProperties properties,
            WebClient.Builder builder, ObjectMapper mapper) {
        return new EvidenceArtifactPublisher(role, store, properties,
                new McpSdkSessionFactory(builder, mapper, role, properties), mapper);
    }
}
