# A2A-185 — Coupure définitive des appels directs

Date de vérification : 2026-09-07  
Release orchestrateur active : `a2a-cutover-b83961c`  
Commit source de l'image active : `b83961cf622b5c4901535f545151ef1ac199026f`

## Résultat

La coupure est franche : les workflows de délégation et de revue enregistrés par le worker Temporal sont
`A2aDelegationWorkflowImpl` et `A2aIndependentReviewWorkflowImpl`. Le pipeline principal utilise lui aussi les
activités A2A pour résoudre les cartes, envoyer les tâches, réconcilier leur état et valider leurs artefacts.

Aucun bean d'exécution locale d'agent, aucune route directe entre agents et aucun sélecteur de transport ou
feature flag de repli `DIRECT/A2A` ne subsiste dans le code ou la configuration de production. Une indisponibilité
A2A ferme donc l'admission ou provoque le retry Temporal ; elle ne peut pas déclencher une exécution en mémoire.

## Contrôles exécutés

Recherche automatisée dans `apps/orchestrator/src/main`, `apps/a2a-agent-runtime/src/main`, `.env.example` et
`infrastructure/compose.yaml` :

```text
AI_FACTORY_A2A_ENABLED|a2a.fleet.enabled|A2A_DISABLED|transportMode|
agentTransport|useA2a|DirectAgent|direct agent|fallback.*agent|agent.*fallback
```

Résultat : aucune occurrence.

Enregistrement des workflows de production :

```text
SoftwareFactoryExecutionWorkflowV1Impl
A2aDelegationWorkflowImpl
PatchIntegrationWorkflowImpl
A2aIndependentReviewWorkflowImpl
```

Suite ciblée :

```shell
cd apps/orchestrator
mvn -B -s .mvn/settings-direct.xml \
  -Dtest=PipelineA2aNonRegressionTest,A2aCutoverConfigurationTest,AgentEffectAdapterArchitectureTest,WorkflowEffectOwnershipTest,A2aWorkerVersioningArchitectureTest \
  test
```

Résultat : `10` tests exécutés, `0` échec, `0` erreur, `0` ignoré.

## Verdict

`PASS` — toute nouvelle invocation d'agent admise par cette release passe exclusivement par A2A 1.0 sous
coordination Temporal ; aucun fallback direct n'est livrable.
