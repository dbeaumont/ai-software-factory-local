# Gate de sortie du lot 8 — observabilité A2A

Date de validation : 2026-09-05

## Navigation de bout en bout

L'API `GET /api/tasks/{taskId}` expose l'identité de l'exécution Temporal (`workflowAttemptId`,
`workflowRunId`), ses délégations et les références immuables Evidence. Pour chaque délégation,
`GET /api/tasks/{taskId}/delegations/{delegationId}/a2a` restitue l'association durable avec le
message, la tâche et le contexte A2A, le digest de l'Agent Card et les mêmes références Evidence.
Le test `TaskControllerTest.navigatesFromTaskAndTemporalExecutionThroughA2aToEvidence` vérifie ce
parcours et le rattachement au bon ticket.

## Cardinalité des dashboards

`python3 scripts/check-a2a-signoz-dashboard.py` valide 15 panneaux et 22 requêtes en refusant les
dimensions non bornées. `./scripts/check-a2a-slo-policy.py` valide les cinq SLO et leurs dimensions
bornées. La validation contre SigNoz accepte 104 requêtes distinctes.

## Alertes et runbooks

`./scripts/check-a2a-runbooks.py` valide huit procédures et les neuf liens d'alertes. Chaque règle
versionne son seuil, sa fenêtre, sa sévérité, son propriétaire et son runbook. La campagne de
fixtures OTLP valide le déclenchement puis le rétablissement automatique de 24 règles sur 24.

## Commandes exécutées

```text
python3 scripts/check-a2a-signoz-dashboard.py
./scripts/check-a2a-slo-policy.py
./scripts/check-a2a-runbooks.py
./scripts/validate-signoz-queries.sh
./scripts/test-signoz-alert-fixtures.sh
```
