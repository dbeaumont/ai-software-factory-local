# A2A-223 — README post-bascule

Date : 2026-09-07

## Verdict

- **Statut : réussi**
- **Liens d'implémentation ajoutés :** vérifiés sur le système de fichiers.
- **Contrôle de format Git :** réussi.

## Contenu actualisé

Le README principal décrit maintenant :

- A2A 1.0 comme unique frontière d'invocation des agents ;
- Temporal comme unique autorité de planification, attente, retry et gate ;
- les quatorze runtimes Compose, leurs Agent Cards, identités, secrets et task queues ;
- le pipeline séquentiel comme parcours métier utilisant lui aussi A2A, et non comme fallback ;
- la séparation entre échanges A2A, outils MCP et activités à effet ;
- le cycle réel de planification, génération, réparation, test et revue par Evidence ;
- les commandes de développement et de diagnostic A2A sur macOS ;
- le rollback vers une release antérieure déjà A2A.

Les liens vers l'ancien `AgentRuntime` et `ToolPermissionMatrix` supprimés ont été remplacés par les activités client
A2A, `AgentExecutionWorker` et `RoleScopedAgentContext`.

## Vérification

```bash
test -f apps/a2a-agent-runtime/src/main/java/com/example/aifactory/agentruntime/AgentExecutionWorker.java
test -f apps/agent-core/src/main/java/com/example/aifactory/agentcore/RoleScopedAgentContext.java
git diff --check
```
