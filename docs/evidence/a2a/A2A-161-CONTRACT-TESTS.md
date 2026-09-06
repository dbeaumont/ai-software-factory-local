# A2A-161 — tests de contrats

Date : 2026-09-05

La gate JDK 25 exécutée par A2A-160 inclut les contrôles de contrats suivants :

- `A2aCatalogCoherenceGateTest` vérifie l'ensemble exact des 14 rôles, leurs cartes générées, skills,
  contrats, URL privées et permissions ; toute divergence de set ferme la gate ;
- `A2aAgentCardVerifierTest` vérifie signature, digest, identité et protocole des Agent Cards ;
- `A2aEnvelopeSchemaTest`, `A2aArtifactReferenceSchemaTest`, `A2aOutputArtifactMappingTest` et
  `A2aExecutionContextSchemaTest` valident les fixtures positives et les mutations négatives avec JSON
  Schema 2020-12 ;
- `A2aBusinessContractGuardTest` valide avant envoi et après réception les 18 documents dorés métier ;
- `A2aInputSkillMappingTest` assure une correspondance exacte et unique des 33 couples rôle/contrat.

Résultat de la campagne contenant ces tests : **82 tests, 0 échec, 0 erreur, 0 ignoré**.

Commande :

```text
docker compose --env-file .env -f infrastructure/compose.yaml build orchestrator
```
