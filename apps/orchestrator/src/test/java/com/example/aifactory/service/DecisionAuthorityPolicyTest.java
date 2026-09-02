package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.aifactory.service.DecisionAuthorityPolicy.Authority.DETERMINISTIC_GATE;
import static com.example.aifactory.service.DecisionAuthorityPolicy.Authority.POLICY;
import static com.example.aifactory.service.DecisionAuthorityPolicy.Authority.SPECIALIST_CONSENSUS;
import static com.example.aifactory.service.DecisionAuthorityPolicy.Authority.SUPERVISOR;
import static com.example.aifactory.service.DecisionAuthorityPolicy.Authority.VERIFIED_EVIDENCE;
import static com.example.aifactory.service.DecisionAuthorityPolicy.Verdict.ALLOW;
import static com.example.aifactory.service.DecisionAuthorityPolicy.Verdict.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionAuthorityPolicyTest {
    private static final String DIGEST = "a".repeat(64);
    private final DecisionAuthorityPolicy policy = new DecisionAuthorityPolicy();

    @Test
    void exposesTheMandatoryAuthorityOrderFromTheVersionedPolicy() {
        assertThat(policy.policyId()).isEqualTo("decision-authority-v1");
        assertThat(policy.policyVersion()).isEqualTo("1");
        assertThat(policy.authorityOrder()).containsExactly(
                DETERMINISTIC_GATE, POLICY, VERIFIED_EVIDENCE, SPECIALIST_CONSENSUS, SUPERVISOR);
    }

    @Test
    void deterministicGateCannotBeOverriddenByLowerAuthorities() {
        DecisionAuthorityPolicy.Resolution result = policy.resolve(List.of(
                claim("supervisor", SUPERVISOR, ALLOW),
                claim("consensus", SPECIALIST_CONSENSUS, ALLOW),
                claim("evidence", VERIFIED_EVIDENCE, ALLOW),
                claim("policy", POLICY, ALLOW),
                claim("gate", DETERMINISTIC_GATE, DENY)));

        assertThat(result.status()).isEqualTo(DecisionAuthorityPolicy.Status.DECIDED);
        assertThat(result.controllingAuthority()).isEqualTo(DETERMINISTIC_GATE);
        assertThat(result.verdict()).isEqualTo(DENY);
        assertThat(result.controllingClaimIds()).containsExactly("gate");
        assertThat(result.ignoredClaimIds()).containsExactly("consensus", "evidence", "policy", "supervisor");
    }

    @Test
    void selectsTheStrongestAuthorityActuallyPresentRegardlessOfInputOrder() {
        List<DecisionAuthorityPolicy.Claim> claims = List.of(
                claim("supervisor", SUPERVISOR, ALLOW),
                claim("evidence", VERIFIED_EVIDENCE, DENY),
                claim("consensus", SPECIALIST_CONSENSUS, ALLOW));

        DecisionAuthorityPolicy.Resolution forward = policy.resolve(claims);
        DecisionAuthorityPolicy.Resolution reverse = policy.resolve(claims.reversed());

        assertThat(forward).isEqualTo(reverse);
        assertThat(forward.controllingAuthority()).isEqualTo(VERIFIED_EVIDENCE);
        assertThat(forward.verdict()).isEqualTo(DENY);
    }

    @Test
    void escalatesInsteadOfSilentlyResolvingAConflictAtTheControllingLevel() {
        DecisionAuthorityPolicy.Resolution result = policy.resolve(List.of(
                claim("policy-a", POLICY, ALLOW),
                claim("policy-b", POLICY, DENY),
                claim("supervisor", SUPERVISOR, ALLOW)));

        assertThat(result.status()).isEqualTo(DecisionAuthorityPolicy.Status.ESCALATE);
        assertThat(result.controllingAuthority()).isEqualTo(POLICY);
        assertThat(result.verdict()).isNull();
        assertThat(result.controllingClaimIds()).containsExactly("policy-a", "policy-b");
        assertThat(result.ignoredClaimIds()).containsExactly("supervisor");
    }

    @Test
    void rejectsMissingDuplicateOrUnauditableClaims() {
        assertThatThrownBy(() -> policy.resolve(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.resolve(List.of(
                claim("same", POLICY, ALLOW), claim("same", POLICY, ALLOW))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.resolve(List.of(
                new DecisionAuthorityPolicy.Claim("claim", POLICY, ALLOW, "not-a-digest"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DecisionAuthorityPolicy.Claim claim(
            String id, DecisionAuthorityPolicy.Authority authority, DecisionAuthorityPolicy.Verdict verdict) {
        return new DecisionAuthorityPolicy.Claim(id, authority, verdict, DIGEST);
    }
}
