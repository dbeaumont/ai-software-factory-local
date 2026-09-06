# Preuve de couverture des rôles A2A

- Date : 2026-09-06
- Candidat vérifié : `690162c`
- Verdict : `PASS`
- Rôles attendus : 14
- Rôles catalogués, enregistrés, déployables et dotés d'une identité : 14

## Résultat par rôle

| Rôle | Catalogue | Registre Compose/GKE | Service privé Compose/GKE | Identité/PKI/carte | Task queue |
|---|---|---|---|---|---|
| `supervisor` | PASS | PASS | PASS | PASS | PASS |
| `architecture-agent` | PASS | PASS | PASS | PASS | PASS |
| `impact-analysis` | PASS | PASS | PASS | PASS | PASS |
| `dependencies-contracts` | PASS | PASS | PASS | PASS | PASS |
| `code-agent` | PASS | PASS | PASS | PASS | PASS |
| `developer` | PASS | PASS | PASS | PASS | PASS |
| `patch-repair` | PASS | PASS | PASS | PASS | PASS |
| `test-agent` | PASS | PASS | PASS | PASS | PASS |
| `test-design` | PASS | PASS | PASS | PASS | PASS |
| `test-evidence` | PASS | PASS | PASS | PASS | PASS |
| `security-agent` | PASS | PASS | PASS | PASS | PASS |
| `threat-model` | PASS | PASS | PASS | PASS | PASS |
| `security-findings` | PASS | PASS | PASS | PASS | PASS |
| `independent-reviewer` | PASS | PASS | PASS | PASS | PASS |

Le rôle `workflow`, de type `control-plane`, est volontairement absent : il reste le contrôle Temporal et n'est
pas un agent A2A.

## Vérifications reproductibles

```shell
ruby scripts/verify-a2a-compose-runtime.rb
ruby scripts/verify-a2a-compose-network.rb
ruby scripts/verify-a2a-gke-manifests.rb
ruby scripts/verify-a2a-gke-discovery.rb
make a2a-secrets
make a2a-pki

mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -Dsurefire.failIfNoSpecifiedTests=false -pl apps/a2a-agent-runtime -am \
  -Dtest=AgentCardCatalogGeneratorTest,AgentRoleAdmissionTest,A2aAgentCardSignerTest test

mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -pl apps/orchestrator \
  -Dtest=A2aCatalogCoherenceGateTest,A2aServiceIdentityRegistryTest,AllowListedAgentRegistryTest test
```

Résultats : les quatre vérificateurs d'infrastructure retournent 14 rôles exacts ; la PKI et les secrets passent
leurs contrôles de permissions et de cohérence ; les 12 tests Java ciblés passent sans échec ni erreur.

## Digests des sources vérifiées

| Source | SHA-256 |
|---|---|
| `resources/agents/catalog-v1.yaml` | `93aca9b64d794cccdf34eea487a54fc350b3ae1e324882d8f1902d7ea795da94` |
| `resources/a2a/agent-registry-v1.json` | `b2e4461c2704867a22ad031ae860d87de78ea762e864a86a50cfdd50b1ccdb67` |
| `infrastructure/a2a/compose-agents.yaml` | `de74d64594538aa7155446f621eb9bef72590648b65e1e79eb28f5bbf8147e42` |
| `infrastructure/gke/a2a/agents.generated.yaml` | `1d5ddfc54fba2183e242d013fd3c6945174e9586f28b05a257678930bd2a1f90` |

Le registre local d'empreintes et les clés privées ne sont pas archivés : ils restent sous `.local`, hors Git. La
preuve retient seulement le succès du vérificateur et les sources publiques reproductibles.
