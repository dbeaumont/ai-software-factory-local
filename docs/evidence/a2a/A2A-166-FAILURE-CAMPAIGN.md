# A2A-166 — campagne de pannes

Date : 2026-09-06

## Résultat

- Build du runtime : **66 tests réussis, 0 échec, 0 erreur, 0 ignoré**.
- Coupure du conteneur agent avant admission : reprise puis admission unique.
- Coupure PostgreSQL après admission : refus fermé, reprise et lecture de la même tâche durable.
- Coupure du réseau privé agent/base : refus fermé borné, reconnexion et lecture de la même tâche.
- Rejeu final du même `messageId` : même `taskId`, une seule ligne durable.
- Temporal : première réconciliation indisponible, seconde réussie, une seule tâche durable.
- LLM : premier appel indisponible, nouvel essai validé sans effet MCP.
- MCP : premier appel indisponible, nouvel essai avec identité et paramètres bornés inchangés.
- Evidence : première publication indisponible, nouvel essai réussi, un seul artefact immuable.

## Automatisation

- `scripts/test-a2a-compose-failures.sh`
- `A2aRecoveryCoordinatorTest.retriesTemporalRecoveryAfterOutageWithoutDuplicatingTheDurableTask`
- `AgentExecutionWorkerTest.retriesCleanlyAfterLlmOutageWithoutDuplicatingAnExternalEffect`
- `RoleScopedMcpClientTest.retriesTheSameBoundedMcpCallAfterATransientOutage`
- `EvidenceArtifactPublisherTest.retriesEvidencePublicationAfterOutageAndKeepsOneImmutableArtifact`
- Journal brut : `docs/evidence/a2a/A2A-166-FAILURE-CAMPAIGN.log`

Les ressources Docker utilisées portent un nom de projet unique et sont supprimées, volumes inclus, à la fin du test.
