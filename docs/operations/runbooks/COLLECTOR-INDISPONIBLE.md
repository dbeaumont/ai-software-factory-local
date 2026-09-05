# Runbook — Collector OpenTelemetry indisponible ou saturé

## Objectif

Maintenir les workflows métier pendant une panne d'observabilité et rétablir un export borné sans agrandir les
files à l'aveugle. La perte de télémétrie est préférable au blocage d'une tâche.

## Diagnostic

```bash
docker compose --env-file .env -f infrastructure/compose.yaml ps otel-collector ingester
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 otel-collector ingester
./scripts/check-signoz-telemetry.sh
```

Dans « AI Factory OpenTelemetry Collector », vérifier les refus, échecs d'export, taille/capacité de file,
mémoire et redémarrages. Confirmer séparément que `/actuator/health` répond sur les applications.

## Rétablissement

1. Corriger d'abord la destination OTLP, le réseau ou le stockage ; ne pas augmenter les files avant d'avoir
   identifié la cause.
2. Redémarrer uniquement le Collector si l'ingester est prêt :
   `docker compose --env-file .env -f infrastructure/compose.yaml restart otel-collector`.
3. Vérifier la reprise de `otelcol_exporter_sent_metric_points` et l'absence de nouveaux échecs.
4. Comparer la fenêtre affectée à l'audit métier ; ne jamais inventer les points perdus.

La file d'export locale est persistée dans le volume `otel-collector-queue`, bornée à 2 048 éléments et compactée
au démarrage/rebond. Le protocole OTLP n'offre pas de dead-letter queue native dans ce déploiement : après 60 s de
retry, les pertes sont comptées et alertées, sans conserver de payload rejeté dans un stockage secondaire.

Déclencher un rollback complet du commit si la configuration empêche le démarrage métier. Ne jamais réactiver
Prometheus ou Grafana en parallèle.
