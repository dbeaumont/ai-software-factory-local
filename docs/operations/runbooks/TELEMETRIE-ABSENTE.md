# Runbook — télémétrie absente, retardée ou rejetée

## Diagnostic par segment

1. Vérifier la santé de l'application et la présence de `OTEL_EXPORTER_OTLP_*_ENDPOINT`.
2. Vérifier la santé du Collector et ses compteurs `accepted`, `failed`, `refused` et `sent` dans SigNoz.
3. Vérifier l'ingester, ClickHouse et PostgreSQL avec `docker compose ... ps`.
4. Exécuter `./scripts/check-signoz-telemetry.sh` puis `./scripts/validate-signoz-queries.sh`.
5. Rechercher par `service.name`, puis passer de la trace aux logs avec `trace_id` ; ne pas utiliser un
   identifiant de tâche comme dimension métrique.

## Confinement et rétablissement

- Conserver stdout comme preuve de secours et noter précisément la fenêtre affectée.
- Ne pas journaliser de payload OTLP rejeté pour diagnostiquer : relever seulement type, compteur et cause.
- Corriger certificat, endpoint, quota ou schéma, puis vérifier que le délai d'apparition repasse sous 60 s.
- Si le backend reste indisponible, poursuivre les workflows et ouvrir un incident de perte bornée.

La clôture exige une métrique, une trace et un log nouveaux dans SigNoz, une file revenue sous 50 % et aucune
donnée sensible visible dans les attributs.
