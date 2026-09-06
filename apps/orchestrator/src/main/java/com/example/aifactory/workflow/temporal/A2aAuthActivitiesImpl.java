package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aAuthenticatedSender;
import com.example.aifactory.a2a.A2aAuthGrant;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aCredentialVault;
import com.example.aifactory.a2a.A2aTaskAssociationStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Resolves and clears the secret entirely inside the activity worker. */
public final class A2aAuthActivitiesImpl implements A2aAuthActivities.ResumeAuth {
    private final A2aTaskAssociationStore associations;
    private final A2aCredentialVault vault;
    private final A2aAuthenticatedSender sender;

    public A2aAuthActivitiesImpl(A2aTaskAssociationStore associations, A2aCredentialVault vault,
                                 A2aAuthenticatedSender sender) {
        this.associations = associations;
        this.vault = vault;
        this.sender = sender;
    }

    @Override
    public A2aContracts.TaskSnapshot resumeAuth(A2aAuthActivities.ResumeRequest request) {
        requireBound(request);
        char[] token = vault.consume(request.grant());
        if (token == null || token.length < 16) throw new SecurityException("A2A credential grant is unavailable");
        try {
            A2aContracts.TaskSnapshot result = sender.send(request.command(), token).toCompletableFuture()
                    .get(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
            if (!request.grant().taskId().equals(result.taskId())
                    || !request.grant().contextId().equals(result.contextId())) {
                throw new SecurityException("A2A authenticated continuation changed task correlation");
            }
            return result;
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("A2A authenticated continuation timed out", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A authenticated continuation interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("A2A authenticated continuation failed", failure.getCause());
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private void requireBound(A2aAuthActivities.ResumeRequest request) {
        if (request == null || request.execution() == null || request.command() == null || request.grant() == null
                || request.command().taskId() == null || request.command().contextId() == null
                || !request.grant().operation().equals(request.command().skillId())
                || request.grant().expiresAt().isBefore(Instant.now())) {
            throw new SecurityException("A2A authenticated continuation grant is invalid");
        }
        A2aAuthGrant grant = request.grant();
        A2aTaskAssociationStore.Association association = associations
                .findByDelegation(request.execution().delegationId())
                .orElseThrow(() -> new SecurityException("A2A authenticated continuation is uncorrelated"));
        if (!grant.agentRole().equals(association.agentRole()) || !grant.taskId().equals(association.a2aTaskId())
                || !grant.contextId().equals(association.a2aContextId())
                || !grant.agentRole().equals(request.command().agentRole())
                || !grant.taskId().equals(request.command().taskId())
                || !grant.contextId().equals(request.command().contextId())) {
            throw new SecurityException("A2A authenticated continuation grant binding differs");
        }
    }
}
