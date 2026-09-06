package com.example.aifactory.a2a;

import java.util.concurrent.CompletionStage;

/** Resolves and verifies the allow-listed Agent Card for a catalog role. */
public interface AgentCardResolver {

    CompletionStage<A2aContracts.AgentCardDescriptor> resolve(String agentRole);
}
