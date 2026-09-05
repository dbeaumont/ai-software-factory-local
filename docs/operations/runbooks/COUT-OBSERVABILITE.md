# Runbook — cardinalité, volume ou coût d'observabilité anormal

## Confinement

Identifier le signal, le service et l'attribut responsables. Réduire l'échantillonnage des traces ou désactiver
une instrumentation optionnelle ; ne pas couper les métriques SLO, l'audit ou les alertes de sécurité.

## Contrôles

- comparer séries, points, spans et logs par `service.name` à la baseline versionnée ;
- rechercher toute dimension contenant `task_id`, `run_id`, `execution_id`, `trace_id` ou `span_id` ;
- vérifier taille des files Collector, disque ClickHouse et rétention bornée (30 jours métriques, 15 jours
  traces/logs au maximum en local) ;
- confirmer que prompts, réponses, code, patchs, preuves, secrets et jetons restent absents.

## Correction

Remplacer une valeur libre par l'énumération autorisée ou déplacer l'identifiant vers trace/log. Déployer le
contrat et le producteur ensemble, puis vérifier le retour à la baseline sur deux fenêtres. Une augmentation de
rétention ou de budget requiert l'approbation du propriétaire plateforme ; une diminution destructive suit une
procédure séparée avec sauvegarde explicite.
