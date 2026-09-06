# Preuve A2A-146 — alertes SigNoz A2A

Date : 2026-09-06

## Couverture livrée

Neuf règles SigNoz versionnées et provisionnées couvrent l'absence de poller, l'agent non prêt, la carte
invalide, le taux d'échec, le backlog, une tâche bloquée, une notification en retard, une collision
d'idempotence et une divergence d'état. Chaque règle déclare une sévérité, une fenêtre d'évaluation, le
propriétaire `agent-platform` et un lien vers le runbook A2A.

Le runtime expose en complément les jauges bornées de readiness, backlog et âge de la tâche active la plus
ancienne, ainsi qu'un compteur de collisions d'idempotence. Les collisions sont comptées lorsque la même clé
est réutilisée avec un contenu ou une tâche cible différent.

## Qualification

Les fixtures OTLP déterministes font franchir le seuil de chacune des 24 règles métriques SigNoz, puis valident
leur rétablissement automatique. La validation de requêtes couvre dashboards et alertes avant provisionnement.

```text
Validated 99 unique SigNoz dashboard and alert queries.
Validated 24/24 SigNoz alert rules firing and automatic recovery with deterministic OTLP metrics.
SigNoz telemetry ready: metrics=792 dashboards=8 alerts=30 retention=720h/360h/15d
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

L'image du runtime A2A et celle du bootstrap SigNoz ont été reconstruites avec JDK 25. Le bootstrap idempotent a
créé les neuf nouvelles règles dans l'instance locale et s'est terminé avec le code `0`.
