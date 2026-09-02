# ADR-MAH-005 — Modes d'exécution et bascule progressive

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : admission, exécution, évaluation et rollback des tâches

## Contexte

La dernière campagne d'outils agentiques a reçu un verdict `REJECTED`. La nouvelle architecture ne peut donc pas
remplacer directement le pipeline existant. Plusieurs modes explicites sont nécessaires pour observer, comparer,
activer progressivement et désactiver le chemin hiérarchique sans contourner les gates.

## Décision

L'orchestrateur expose un mode global maximal et calcule pour chaque tâche un mode effectif inférieur ou égal à
ce plafond. Aucun ticket ou résultat de modèle ne peut augmenter ce niveau.

| Mode | Exécution de référence | Exécution hiérarchique | Autorité sur le résultat |
|---|---|---|---|
| `PIPELINE` | Oui | Non | Pipeline déterministe |
| `HIERARCHICAL_SHADOW` | Oui | Oui, sans effet | Pipeline déterministe uniquement |
| `HIERARCHICAL_CANARY` | Oui pour comparaison ou secours | Oui sur allow-list | Hiérarchique si toutes les gates canary sont satisfaites |
| `HIERARCHICAL_ACTIVE` | Chemin de secours contrôlé | Oui selon routage | Hiérarchique, avec chemin court possible |

## Règles communes

1. Le mode par défaut reste `PIPELINE` tant que la qualification n'est pas `QUALIFIED`.
2. Le mode effectif est calculé par l'hôte depuis configuration, qualification, dépôt, classe de risque et règle
   de canary ; le modèle ne fournit jamais cette valeur.
3. Tous les modes utilisent les mêmes contrôles déterministes de patch, tests, qualité, sécurité et livraison.
4. `HIERARCHICAL_SHADOW` ne modifie ni workspace d'intégration, ni décision, ni effet SCM.
5. Les sorties shadow sont stockées avec une tentative et un espace de preuves distincts.
6. Le mode canary est limité par allow-list de dépôts, pourcentage stable, classe de risque et plafond de coût.
7. Un rollback arrête les nouvelles admissions hiérarchiques mais ne transforme jamais une preuve incomplète en
   succès et ne répète pas un effet déjà confirmé.

## Configuration cible

```text
AI_FACTORY_WORKFLOW_MODE=PIPELINE
AI_FACTORY_HIERARCHICAL_QUALIFICATION=INCOMPLETE
AI_FACTORY_HIERARCHICAL_CANARY_PERCENT=0
AI_FACTORY_HIERARCHICAL_ALLOWED_REPOSITORIES=
AI_FACTORY_HIERARCHICAL_ALLOWED_RISKS=R0,R1
```

Les valeurs inconnues, incohérentes ou supérieures au verdict de qualification empêchent le démarrage ou
l'admission. Le mode ne peut pas être modifié par une donnée provenant du ticket ou du dépôt.

## Transitions autorisées

```text
PIPELINE
  -> HIERARCHICAL_SHADOW
  -> HIERARCHICAL_CANARY
  -> HIERARCHICAL_ACTIVE
```

Le retour vers un mode inférieur est toujours autorisé par l'exploitation. Le passage vers un mode supérieur
exige la gate du niveau précédent, un rapport d'évaluation et une révision de configuration auditée.

## Traitement des tâches en cours lors d'un rollback

- une tâche shadow continue ou est annulée sans affecter la baseline ;
- une tâche canary sans effet en attente est annulée ou terminée selon la politique d'incident ;
- une tâche en attente d'approbation conserve son manifeste mais aucune nouvelle livraison n'est déclenchée si le
  kill switch concerné est actif ;
- une opération SCM déjà acceptée est retrouvée par idempotency key, jamais répétée aveuglément ;
- toute reprise sous un autre mode crée une nouvelle tentative liée au même ticket et au même commit source.

## Conséquences

- les métriques et preuves doivent toujours porter mode demandé et mode effectif ;
- l'UI doit distinguer clairement résultat de référence, résultat shadow et résultat actif ;
- le pipeline historique reste maintenu jusqu'à la fin de la période de stabilisation ;
- la configuration de canary et les changements de mode deviennent des événements d'audit.
