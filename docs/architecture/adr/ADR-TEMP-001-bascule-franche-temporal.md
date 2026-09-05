# ADR-TEMP-001 — Temporal comme moteur unique d'orchestration

- Statut : accepté
- Date : 2026-09-05
- Portée : admission et exécution des tickets de l'AI Software Factory

## Contexte

Le dépôt contient un pipeline local coordonné par `DeterministicWorkflowCoordinator` ainsi que des workflows
Temporal testés, mais non raccordés au parcours public. Les modes d'exécution multi-agent décrivent la stratégie
métier d'une tâche ; ils ne doivent pas choisir son moteur technique.

## Décision

1. Temporal devient l'unique moteur d'orchestration des tickets après la fenêtre de bascule.
2. Le moteur n'est ni sélectionnable par un ticket, ni modifiable par une sortie de modèle.
3. `executionMode` reste une dimension métier indépendante : il décrit la stratégie d'agents exécutée à
   l'intérieur du workflow Temporal.
4. L'orchestrateur ne conserve aucun routage `LOCAL`/`TEMPORAL`, aucun shadow et aucun canary de moteur.
5. La configuration, l'API, les métriques et l'interface distinguent explicitement moteur d'orchestration et mode
   métier tant que les anciens enregistrements doivent encore être lus.
6. `DeterministicWorkflowCoordinator` est retiré dans la release de coupure, après extraction de ses étapes en
   activités réutilisables et qualification hors trafic.

Cette décision remplace les dispositions transitoires de `ADR-MAH-001`, `ADR-MAH-002` et `ADR-MAH-005` qui
maintenaient un coordinateur local, une activation progressive ou un moteur dépendant du mode hiérarchique.

## Conséquences

- Temporal est une dépendance obligatoire de la readiness et de l'admission.
- Une indisponibilité Temporal ferme les admissions au lieu de déclencher un fallback local.
- Le rollback restaure une version compatible des workers Temporal ; il ne change pas de moteur.
- Les tests du pipeline local restent une baseline avant coupure, mais ne constituent pas un chemin de production
  après la migration.

## Vérification

- aucun contrat public ne permet de choisir le moteur ;
- chaque nouvelle tâche post-bascule possède un Workflow ID Temporal ;
- les modes métier restent portés par des données validées par l'hôte ;
- aucun bean local alternatif n'est disponible dans la release finale.
