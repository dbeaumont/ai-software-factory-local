# A2A-091 — Stratégie de déterminisme et Worker Versioning

## Décision

La coupure A2A utilise le Worker Versioning de Temporal et le comportement `PINNED`. Le type public des child
workflows reste stable (`DelegationWorkflow` et `IndependentReviewWorkflow`) ; le nouveau build enregistre les
implémentations A2A. Un historique commencé par un ancien build reste routé vers ce build jusqu'à sa terminaison.
Il n'est jamais rejoué par l'implémentation A2A.

`AI_FACTORY_TEMPORAL_BUILD_ID` doit être l'identifiant immuable de la release (SHA Git complet ou identifiant
d'image digesté), et `AI_FACTORY_TEMPORAL_DEPLOYMENT_NAME` reste stable pour la lignée de l'orchestrateur.

## Procédure de coupure

- [x] Construire et déployer le nouveau worker avec un `build-id` immuable distinct.
- [x] Vérifier que les anciens et nouveaux builds sont enregistrés sur chaque task queue.
- [x] Positionner le nouveau build comme version courante pour les nouveaux workflows.
- [x] Conserver les pollers de l'ancien build tant que ses workflows `PINNED` ne sont pas tous terminaux.
- [x] Interdire toute suppression d'un ancien build tant qu'une exécution ouverte lui est assignée.
- [x] En cas d'incident, suspendre les nouvelles admissions avant de changer la version courante ; ne jamais
  réintroduire un fallback d'agent Java local dans le nouveau build.

## Preuves automatisées

- `A2aWorkerVersioningArchitectureTest` vérifie le comportement `PINNED`, l'enregistrement exclusif des
  implémentations A2A dans le nouveau registre et l'activation de Worker Versioning.
- `TemporalWorkerRegistryTest.keepsOldAndNewCompatibleBuildIdsRegisteredOnEveryQueueAtTheSameTime` vérifie que
  deux builds distincts peuvent être présents simultanément sur toutes les queues pendant le drainage.
- Les tests de référence de replay restent attachés aux anciennes implémentations et ne sont pas réécrits.
