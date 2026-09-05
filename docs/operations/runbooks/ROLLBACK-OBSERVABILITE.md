# Runbook — rollback complet de l'observabilité

## Portée et autorité

Ce rollback remet **toute la version applicative** sur la dernière chaîne historique qualifiée. Il n'autorise
jamais un mélange entre applications OTLP, Collector/SigNoz et ancienne configuration. Exploitation pilote la
décision ; Sécurité doit approuver si l'incident implique fuite, identité, transport ou donnée sensible. Le délai
de décision est de 15 minutes pour un échec métier causé par la configuration, et de 30 minutes pour une perte de
télémétrie seule puisque le métier continue sans Collector.

Référence locale : tag `observability-prometheus-grafana-final`, commit `06955bec9a6a18f846e2f229893584ebf2051b52`.
La sauvegarde hors dépôt et ses empreintes sont consignées dans
[`grafana-volume-backup-2026-09-05.md`](../../evidence/observability/grafana-volume-backup-2026-09-05.md).

## Conditions de déclenchement

- une configuration d'instrumentation empêche une application de démarrer ou perturbe le chemin métier ;
- une fuite de donnée est confirmée et ne peut pas être confinée par redaction/désactivation immédiate ;
- l'ingestion, une métrique contractuelle ou une alerte critique reste absente au-delà du délai décidé ;
- Collector ou backend restent irrécupérables et l'autorité exige la chaîne antérieure pendant l'enquête.

## Procédure atomique

1. Geler les admissions et consigner heure, commit, tâches en vol, symptômes et décision.
2. Réconcilier tout effet SCM/sandbox à issue inconnue et préserver les logs stdout.
3. Arrêter proprement la version courante avec `docker compose --env-file .env -f infrastructure/compose.yaml down`,
   sans `--volumes`.
4. Vérifier qu'aucun Collector, SigNoz ou ingester du projet ne reste actif.
5. Basculer **le dépôt entier** sur le tag de rollback dans un worktree dédié ; ne pas extraire quelques fichiers.
6. Restaurer le volume historique seulement si nécessaire et uniquement après validation de son SHA-256.
7. Démarrer la pile du tag avec son Compose et ses images/digests, puis vérifier toutes les applications.
8. Reprovisionner un canal de notification unique. Désactiver l'ancien destinataire avant le nouveau afin d'éviter
   tout doublon ; envoyer un événement synthétique puis l'acquitter.
9. Exécuter un scénario nominal et un scénario dégradé sans approuver automatiquement de mutation SCM réelle.
10. Contrôler que les options de capture de prompts, résultats et preuves restent désactivées.

## Interdictions

- ne jamais démarrer les deux backends ou les deux jeux d'applications en parallèle ;
- ne jamais restaurer une base ou un volume sur une version de schéma différente ;
- ne jamais utiliser la sauvegarde pour rechercher prompts, code, preuve ou secret ;
- ne jamais supprimer les volumes courants pendant le rollback.

## Vérification et retour à OpenTelemetry

Le rollback est réussi lorsque le métier est sain, les signaux attendus sont visibles, une alerte synthétique suit
un seul chemin et aucune donnée interdite n'est capturée. Le retour à OpenTelemetry exige cause racine, correction,
test de régression, sauvegarde des deux états et approbation Exploitation/Sécurité selon l'impact.

Le tag et les archives de rollback ne sont supprimés qu'après la date d'expiration, une campagne stable et une
approbation formelle. Leur suppression est irréversible et constitue un ticket distinct.
