# Runbook — rollback du mode multi-agent hiérarchique

## Objectif

Ramener les nouvelles admissions vers `PIPELINE`, empêcher tout effet hiérarchique non réconcilié et préserver
l'historique nécessaire à l'enquête. La politique autoritative est
[`rollback-policy-v1.yaml`](../../resources/multiagents/policies/rollback-policy-v1.yaml).

## Déclenchement immédiat

Déclencher automatiquement le rollback pour tout effet non autorisé ou dupliqué, escalade de permission/scope,
fuite de secret, accès cross-task, preuve ou approbation invalide, contrat invalide accepté ou révocation de la
qualification. Les seuils SLO, échecs, coût, saturation et non-progression Temporal définis dans la politique
déclenchent également le retour sûr.

En cas de doute sur l'état d'un effet, le considérer comme potentiellement exécuté et le réconcilier par clé
d'idempotence. Ne jamais le répéter pour « vérifier ».

## Confinement immédiat

- ouvrir l'incident et noter le commit, les versions/digests, le mode et les tâches en vol ;
- ramener le plafond des nouvelles admissions à `PIPELINE`, la qualification à `INCOMPLETE` et le canary à zéro ;
- activer le kill switch du rôle, mode, outil ou serveur affecté ;
- préserver historiques Temporal, workspaces, journaux et preuves.

## Procédure opérateur

1. Créer l'incident, noter l'heure, le déclencheur, le mode et les tâches potentiellement affectées.
2. Abaisser le plafond d'admission à `PIPELINE`, passer la qualification à `INCOMPLETE` et le canary à zéro.
3. Bloquer les nouveaux workflows hiérarchiques et geler leurs effets externes non confirmés.
4. Suspendre ou annuler les activités enfants ; ne supprimer ni historique Temporal, ni workspace, ni preuve.
5. Router les nouvelles tâches vers le pipeline figé et surveiller sa capacité.
6. Classer chaque tentative en vol selon la table ci-dessous et consigner la décision.
7. Vérifier l'intégrité des manifestes, digests, approbations, journaux et clés d'idempotence.
8. Identifier la cause, les versions exactes et le premier/dernier événement affecté.
9. Corriger sur une nouvelle version, ajouter le test de régression et répéter le rollback en environnement isolé.
10. Reprendre uniquement en `HIERARCHICAL_SHADOW` après les approbations requises.

## Tentatives en vol

| État observé | Action |
|---|---|
| aucun effet demandé | annuler ou suspendre |
| décision humaine attendue | suspendre ; invalider si le digest change |
| effet demandé, résultat inconnu | interroger le système cible avec la clé d'idempotence |
| effet confirmé | enregistrer, ne jamais répéter |
| shadow | annuler sans modifier le résultat de référence |

Tout rejeu par le pipeline crée une nouvelle tentative liée au même ticket et au même commit source. Il ne
réutilise pas implicitement les décisions ou approbations de la tentative hiérarchique.

## Diagnostic

```bash
docker compose -f infrastructure/compose.yaml ps
docker compose -f infrastructure/compose.yaml logs --tail=200 orchestrator temporal
curl -fsS "http://localhost:${ORCHESTRATOR_PORT:-8080}/actuator/health"
```

Déterminer le premier événement fautif, les tâches et effets concernés, puis vérifier journal chaîné, historique
Temporal, digests, manifestes, approbations et clés d'idempotence. Une issue inconnue reste inconnue jusqu'à sa
réconciliation auprès du système cible.

## Rétablissement

Le rétablissement suit la section « Reprise » ci-dessous. La version corrigée repart obligatoirement en
`HIERARCHICAL_SHADOW`; le pipeline reste l'autorité jusqu'à une nouvelle qualification.

## Vérification et clôture

- aucune nouvelle admission hiérarchique ;
- canary à zéro et qualification `INCOMPLETE` ;
- aucune opération SCM/IAM/donnée/déploiement en attente sans propriétaire ;
- nouvelles tâches servies par `PIPELINE` ;
- preuves et historiques lisibles, digests cohérents ;
- alertes, métriques et incident reliés aux tâches affectées.

## Reprise

La reprise exige cause racine, périmètre d'impact, correction versionnée, tests de régression, répétition réussie
du rollback et rapport de qualification courant. Exploitation approuve toujours ; Sécurité et Produit approuvent
selon l'impact. Le retour direct à `HIERARCHICAL_ACTIVE` est interdit.

## Escalade

Exploitation pilote le rollback. Sécurité est obligatoire pour permission, secret, isolation, preuve ou effet
suspect ; Produit l'est pour impact fonctionnel ou client. Un état non déterminé, un effet non réconcilié ou une
preuve non vérifiable interdit la clôture et la remontée de mode.
