# Gate du lot 11 — observabilité, audit et exploitation

- Date de validation : 2026-09-02
- Verdict : `PASS`
- Critère : chaque tâche est traçable de l'intention à la PR sans exposer de secret ou contenu sensible.

> Portée du verdict : corrélation applicative, métriques, dashboards, alertes et audit testés. Aucun exporteur
> OpenTelemetry/OTLP n'est câblé dans le runtime courant et le journal HMAC reste local au processus.

## Chaîne de traçabilité

| Segment | Identité ou liaison vérifiée | Preuve d'implémentation |
|---|---|---|
| intention → workflow | `task_id`, `attempt_id`, `trace_id`, `run_id` | `ExecutionIdentity`, historique Temporal |
| workflow → délégation | `delegation_id`, `agent_run_id`, parent et rôle | DAG validé et spans Workflow/Child Workflow/Activity |
| délégation → LLM/MCP | identité d'exécution propagée, outil et serveur bornés | `ExecutionTracer`, enveloppes MCP validées |
| sandbox → preuve | `execution_id`, digest de sortie, statut complet | `McpSandboxService`, Evidence MCP |
| preuve → assurance | URI et digest liés au verdict | `DeliveryCorrelationVerifier` |
| assurance → manifeste | décision, patch et artefacts liés à la tentative | manifeste Evidence MCP |
| manifeste → PR | commit, dépôt, identifiant et URL SCM | vérification locale avant corrélation finale |

`DeliveryCorrelationVerifier` ne produit un identifiant de corrélation qu'après validation de la chaîne complète
job sandbox → preuves → assurance → manifeste → livraison SCM. L'intention initiale et chaque branche agentique
restent rattachées à la même identité durable.

## Confidentialité et cardinalité

- la capture des prompts, résultats et preuves est désactivée par défaut dans Spring et Compose ; les variables
  réservées à une future instrumentation OpenTelemetry suivent aussi cette valeur sûre ;
- spans et métriques ne transportent que des identifiants et métadonnées bornés ;
- rôles, outcomes, raisons d'arrêt, périmètres et événements proviennent de listes fermées ;
- l'interface expose les références et digests de preuves, jamais leur contenu brut ;
- les runbooks interdisent de recopier contenu sensible ou secret dans les tickets d'incident.

## Audit de sécurité

Les autorisations, refus, approbations et changements de mode sont écrits dans une chaîne HMAC append-only et
séquencée. Toute modification d'une entrée déjà chaînée est détectée. Pour ce prototype, le journal est local au
processus : la durabilité externe/WORM reste une exigence d'industrialisation et n'est pas revendiquée par ce gate.

## Exploitabilité

- six dashboards Grafana : global, Supervisor, agents, Temporal, MCP et sandbox ;
- sept alertes Prometheus : boucle, budget, coût, backlog, heartbeat, contrat et preuve altérée ;
- six runbooks actuels : saturation, agent défaillant, Temporal indisponible, MCP compromis, rollback et incident
  canary/kill switch ;
- seuils et temporisations versionnés dans `infrastructure/observability/alerts/multiagents.yml` ;
- règles montées en lecture seule dans Prometheus et validées par `promtool`.

## Preuves automatisées

| Preuve | Résultat |
|---|---|
| `ExecutionIdentityTest` et `ExecutionTracerTest` | Corrélation stable aux frontières Workflow, Activity, LLM et MCP. |
| `DeliveryCorrelationVerifierTest` | Refus de toute rupture entre sandbox, preuves, assurance, manifeste et PR. |
| `HashChainedSecurityAuditJournalTest` | Détection d'une entrée altérée dans la chaîne HMAC. |
| `ObservabilityContentPropertiesTest` | Capture de contenu désactivée par défaut. |
| `MultiAgentDashboardsTest` | Six dashboards lisibles avec requêtes actionnables. |
| `MultiAgentAlertRulesTest` | Sept alertes structurées, annotées et montées dans Prometheus. |
| `MultiAgentRunbooksTest` | Cinq procédures du périmètre initial complètes et reliées aux alertes ; le runbook canary a été ajouté ensuite. |
| `promtool check rules` | `SUCCESS` — 7 règles reconnues. |
| Suite Maven de l'orchestrateur | `PASS` — 380 tests, 0 échec, 0 erreur. |

## Conclusion

Le lot 11 satisfait le critère du prototype : une exécution est explicable de son intention à sa PR par des
identités stables, des digests et des décisions vérifiées, tandis que la collecte de contenu reste opt-in. Les
alertes conduisent à des procédures qui préservent historique, preuves, permissions et idempotence.
