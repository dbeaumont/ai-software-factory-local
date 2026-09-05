# OTEL-075 — sauvegarde et restauration isolée de SigNoz

## Campagne

La commande `./scripts/test-signoz-backup-restore.sh` a été exécutée le 5 septembre 2026. Elle a :

1. arrêté proprement l'ingester, l'interface SigNoz, ClickHouse, PostgreSQL et ClickHouse Keeper ;
2. archivé en lecture seule les quatre volumes persistants avec une image BusyBox épinglée par digest ;
3. produit et vérifié un manifeste SHA-256 et le commit source ;
4. redémarré le backend actif ;
5. restauré les quatre archives dans des volumes Docker isolés à noms uniques ;
6. vérifié que chaque volume restauré contenait des données ;
7. supprimé les volumes temporaires et revalidé l'ingestion, les dashboards, les alertes et les rétentions.

Résultat : **PASS**. Aucun volume actif n'a été écrasé et les applications métier n'ont pas été arrêtées.

La sauvegarde de qualification est conservée à
`/private/tmp/ai-factory-signoz-backup-test-FQdL6U`. Elle contient environ 770 Mio de données sources avant
compression ; elle est locale, hors Git et doit être protégée comme une sauvegarde potentiellement sensible.

## Commandes versionnées

- `scripts/backup-signoz.sh <répertoire-vide>` crée une copie cohérente et redémarre le backend même en cas d'erreur ;
- `scripts/restore-signoz-backup-isolated.sh <sauvegarde> <préfixe>` refuse tout volume existant et ne touche jamais
  aux volumes actifs ;
- la restauration en place reste une opération d'incident manuelle soumise au runbook et à une validation explicite.

## Rétention, compaction et suppression

`./scripts/test-signoz-data-lifecycle.sh` crée une base ClickHouse jetable dont le nom est borné au préfixe
`ai_factory_otel_lifecycle_`, sans modifier les tables SigNoz actives. Il insère un marqueur expiré et un marqueur
récent, matérialise le TTL, force une compaction `FINAL`, vérifie que seul l'expiré disparaît, exécute une mutation
de suppression contrôlée sur le marqueur restant, compacte à nouveau et exige une table vide. Un `trap` détruit
uniquement cette base de test, succès ou échec. La campagne du 5 septembre 2026 est **PASS**.

## Limites

Cette campagne valide les archives, empreintes, données restaurées et primitives locales de cycle de vie, mais ne
constitue pas encore un PRA complet : elle ne redémarre pas une seconde pile SigNoz sur les volumes restaurés et ne
teste pas la perte d'une zone GKE.
