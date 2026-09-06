# A2A-220 — Suppression du runtime agent direct

Date : 2026-09-07

## Verdict

- **Statut : réussi**
- **Suite complète orchestrateur :** 548 tests, 0 échec, 0 erreur, 1 test ignoré.
- **Compilation du réacteur :** réussie pour `apps/orchestrator` et ses dépendances.

## Éléments supprimés

L'orchestrateur ne contient plus :

- `AgentRuntime`, `AgentExecutor` et la boucle `AgentToolLoop` ;
- les wrappers Java des dix rôles auparavant invoqués en mémoire ;
- `AgentContextToolHost`, `ToolPermissionMatrix` et les métriques propres au runtime direct ;
- le chargement local des prompts, le format de réponse planner et les méthodes de complétion du client LLM ;
- les propriétés et gardes de qualification `ai-factory.agent-tools` ;
- les tests unitaires qui instançaient le runtime direct.

Le bean `LlmGatewayClient` ne conserve que le diagnostic réactif `cloudAvailabilityAsync`. Il n'expose plus de
méthode `chat`, de boucle d'outils ou d'appel bloquant. Les variables devenues sans consommateur ont été retirées
de `.env.example`, de la configuration Spring et de Compose.

## Prévention de régression

`AgentArchitectureRulesTest` exige que les types et propriétés supprimés restent absents et que le client LLM de
l'orchestrateur n'expose que la vérification de disponibilité. `PipelineA2aNonRegressionTest` interdit en parallèle
toute référence aux anciens agents dans les chemins de contrôle et toute invocation LLM depuis le pipeline.

## Reproductibilité

```bash
mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -pl apps/orchestrator test
```
