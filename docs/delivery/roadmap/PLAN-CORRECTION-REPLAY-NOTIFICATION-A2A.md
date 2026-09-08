# Plan de correction — replay divergent des notifications A2A

## Objectif

Corriger le faux positif `Divergent A2A workflow notification replay` produit lorsqu'une même transition A2A
est observée d'abord par réconciliation `tasks/get`, puis par callback authentifié, avec des différences limitées
aux données de transport comme l'horodatage ou la provenance.

Le changement doit préserver les protections existantes contre une véritable divergence, rester compatible avec
les historiques Temporal en cours et permettre au workflow actuellement bloqué de reprendre sans recréer la tâche
A2A distante.

## Périmètre

- [x] Modifier uniquement la déduplication inter-canaux dans `A2aTaskAwaiter`.
- [x] Conserver la validation stricte des callbacks dans `PostgresA2aNotificationInbox`.
- [ ] Ajouter les tests unitaires, Temporal embarqués et de replay nécessaires.
- [ ] Vérifier la compatibilité du changement avec l'historique réel en échec.
- [ ] Déployer une nouvelle version de l'orchestrateur et laisser Temporal reprendre le workflow.
- [ ] Mettre à jour la documentation d'exploitation après validation.

## Hors périmètre

- [ ] Ne pas modifier la sémantique des séquences A2A.
- [ ] Ne pas désactiver HMAC, la corrélation, le contrôle des digests ou la protection anti-rejeu de l'inbox.
- [ ] Ne pas supprimer ou réécrire manuellement l'historique Temporal.
- [ ] Ne pas recréer la tâche A2A distante déjà terminée.
- [ ] Ne pas modifier manuellement les tables de tâches, d'associations ou de notifications.
- [ ] Ne pas relâcher les contrôles sur une divergence d'état, de contexte, de rôle ou d'artefacts.

## Diagnostic établi

- [x] Identifier le workflow en échec : `ai-factory/fbece50e/pipeline-1`.
- [x] Identifier le run en échec : `01a08063-d355-756f-8e11-186c5712ab5d`.
- [x] Localiser l'exception dans `A2aTaskAwaiter.accept`.
- [x] Vérifier que l'échec est rejoué à chaque nouvelle tentative de Workflow Task.
- [x] Vérifier que le signal fautif porte la transition `architecture-agent`, séquence `2`, état `COMPLETED`.
- [x] Vérifier que `A2aGetTask` avait déjà observé la même transition terminale.
- [x] Constater l'écart d'horodatage entre le snapshot et le callback :
  `2026-09-08T09:40:49.748548Z` contre `2026-09-08T09:40:49.751606Z`.
- [x] Constater la différence de provenance : metadata synthétique `source=getTask` contre metadata de callback vide.
- [x] Confirmer que `Notification.equals()` compare actuellement tous les composants du record, y compris
  `occurredAt` et `metadata`.
- [x] Confirmer que la tâche applicative `fbece50e` reste en `PLANNING` avec une projection devenue obsolète.

## 1. Formaliser l'identité d'une transition A2A

- [x] Documenter dans `A2aTaskAwaiter` qu'une séquence identifie une transition protocolaire pour un couple
  `agentRole/taskId`.
- [x] Définir les champs qui doivent être strictement identiques pour reconnaître la même transition :
  - [x] `agentRole` ;
  - [x] `taskId` ;
  - [x] `contextId` ;
  - [x] `sequence` ;
  - [x] `state` ;
  - [x] `artifacts`.
- [x] Définir les champs qui ne participent pas à l'identité inter-canaux :
  - [x] `occurredAt`, car le task store et l'outbox peuvent matérialiser la même transition à des instants
    légèrement différents ;
  - [x] `metadata`, car `dispatch`, `getTask` et le callback peuvent porter une provenance différente.
- [x] Confirmer que les artefacts restent comparés intégralement, métadonnées d'artefact comprises.
- [x] Confirmer qu'une différence de `state`, `contextId`, `agentRole` ou `artifacts` reste une divergence de
  sécurité.
- [x] Consigner cette règle dans un commentaire court à proximité de l'implémentation, sans dupliquer le runbook.

## 2. Modifier `A2aTaskAwaiter`

- [x] Ajouter une méthode déterministe et sans effet de bord, par exemple
  `sameTransition(Notification current, Notification incoming)`.
- [x] Faire comparer explicitement par cette méthode les six champs constituant l'identité métier.
- [x] Remplacer l'utilisation de `notification.equals(current)` dans `accept` par cette comparaison explicite.
- [x] Conserver le comportement suivant dans `accept` :
  - [x] notification nulle : ignorer ;
  - [x] aucune notification courante : enregistrer la notification ;
  - [x] séquence supérieure : enregistrer la nouvelle notification ;
  - [x] même séquence et même transition métier : ignorer le doublon ;
  - [x] même séquence et transition métier différente : lever `SecurityException` ;
  - [x] séquence inférieure : ignorer la notification tardive.
- [x] Conserver la notification déjà issue de `getTask` lorsqu'un callback équivalent arrive ensuite.
- [x] Ne pas introduire d'appel non déterministe, d'accès à l'horloge système, d'I/O ou d'état statique dans le
  code du workflow.
- [x] Ne pas changer le message de l'exception de divergence afin de préserver recherches, alertes et runbooks.
- [x] Vérifier le formatage et les imports inutilisés après modification.

## 3. Préserver la frontière de sécurité de l'inbox

- [x] Vérifier que `PostgresA2aNotificationInbox.admit` continue à comparer le digest canonique de deux callbacks
  portant la même séquence.
- [x] Vérifier qu'un callback rejoué avec un payload modifié reste rejeté avant l'envoi du signal Temporal.
- [x] Vérifier qu'un callback hors ordre reste rejeté conformément au contrat actuel de l'inbox.
- [x] Vérifier que la nouvelle équivalence n'est utilisée que dans l'état interne du workflow Temporal.
- [x] Ne pas mutualiser la méthode d'équivalence avec l'inbox : les deux composants protègent des frontières
  différentes.
- [x] Ajouter, si nécessaire, un commentaire expliquant que l'inbox compare des livraisons du même canal alors que
  l'awaiter rapproche des représentations issues de canaux différents.

## 4. Ajouter les tests unitaires de régression

Étendre `A2aTaskAwaiterTest` avec des données fixes et des timestamps explicites.

- [x] Ajouter un test `getTask` puis callback de même séquence, état, contexte et artefacts, mais avec des
  `occurredAt` différents : le callback est accepté comme doublon.
- [x] Ajouter un test équivalent avec des `metadata` différents : le callback est accepté comme doublon.
- [x] Combiner les deux différences dans un cas reproduisant exactement l'incident : metadata
  `source=getTask`, timestamp du snapshot, puis metadata vide et timestamp de l'outbox.
- [x] Ajouter le cas symétrique callback puis réconciliation équivalente.
- [x] Vérifier que la notification conservée reste la première notification acceptée.
- [x] Vérifier qu'une notification de séquence inférieure est ignorée sans altérer la notification courante.
- [x] Conserver ou renforcer le test de rejet d'un état différent pour une même séquence.
- [x] Ajouter un rejet pour un `contextId` différent à séquence identique.
- [x] Ajouter un rejet pour un `agentRole` différent à séquence identique.
- [x] Ajouter un rejet pour une liste d'artefacts différente à séquence identique.
- [x] Ajouter un rejet pour un contenu d'artefact différent à séquence identique.
- [x] Vérifier qu'un doublon strictement identique reste accepté.

## 5. Ajouter un scénario Temporal embarqué

- [x] Créer un workflow de test qui initialise une tâche en `SUBMITTED/0`.
- [x] Faire retourner par l'activité simulée `GetTask` un snapshot `COMPLETED/2` contenant les transitions
  `WORKING/1` puis `COMPLETED/2`.
- [x] Laisser `applyReconciliation` installer la transition terminale synthétique avec `source=getTask`.
- [x] Envoyer ensuite un signal `a2aTaskUpdate` `COMPLETED/2` portant un timestamp différent et des metadata vides.
- [x] Vérifier que le Workflow Task traitant le signal se termine sans exception.
- [x] Vérifier que le workflow ne réexécute pas l'activité A2A distante.
- [x] Vérifier que le résultat terminal et les artefacts restent ceux déjà réconciliés.
- [x] Vérifier que l'activité suivante n'est planifiée qu'une fois.
- [x] Ajouter un scénario négatif où le signal `COMPLETED/2` contient un artefact différent et doit encore échouer.

## 6. Tester le replay de l'historique réel

> Blocage constaté le 2026-09-08 : Temporal ne retrouve plus le workflow ni le run incidentés, et la base de
> projection courante ne contient plus `fbece50e`. L'export réel est donc indisponible dans cet environnement.
> Le scénario synthétique équivalent est rejoué par `A2aTaskAwaiterTest`, mais ne remplace pas cette preuve réelle.

- [ ] Exporter l'historique JSON complet du run avant toute opération de rétablissement.
- [ ] Stocker la copie de travail hors des sources versionnées si elle contient des identifiants ou données
  d'incident non destinés au dépôt.
- [x] Ajouter un test de replay avec le worker et le code corrigés, ou utiliser l'outil de replay Temporal déjà
  adopté par le projet.
- [ ] Vérifier que le replay franchit l'événement `56`, signal `a2aTaskUpdate`, sans
  `Divergent A2A workflow notification replay`.
- [x] Vérifier qu'aucune commande Temporal nouvelle ou différente n'est produite pour les événements déjà
  complétés.
- [x] Vérifier que l'activité de validation déjà planifiée n'est pas dupliquée.
- [x] Vérifier si `Workflow.getVersion()` est réellement inutile pour ce changement.
- [ ] Si le replay révèle une incompatibilité avec un historique déjà complété, arrêter la livraison et concevoir
  un chemin versionné avant de déployer.

## 7. Exécuter les validations locales

- [x] Exécuter les tests ciblés de `A2aTaskAwaiterTest`.
- [x] Exécuter les tests ciblés de `TemporalA2aNotificationReceiverTest`.
- [x] Exécuter les tests ciblés de l'inbox PostgreSQL.
- [ ] Exécuter la suite de tests du module `apps/orchestrator`.
- [ ] Exécuter les contrôles de formatage et d'analyse statique applicables au module.
- [ ] Vérifier `git diff --check`.
- [ ] Examiner le diff pour confirmer qu'aucun fichier sans rapport n'a été modifié.
- [ ] Conserver dans le compte rendu les commandes exécutées, leurs résultats et les éventuels tests non exécutés.

## 8. Préparer la livraison

- [ ] Produire un nouveau build immuable de l'orchestrateur.
- [ ] Utiliser un nouveau `buildId` Temporal ; ne pas réutiliser `a2a-cutover-d4b55c7`.
- [ ] Vérifier que le nouveau worker poll bien `ai-factory-workflows` dans le namespace `ai-factory-local`.
- [ ] Vérifier la politique de versioning/pinning avant de router le workflow bloqué vers le nouveau build.
- [ ] Préparer un retour au build précédent pour les workflows non concernés.
- [ ] Préparer une fenêtre de surveillance couvrant au minimum deux cycles de réconciliation A2A.
- [ ] Informer l'exploitation que le workflow bloqué doit reprendre automatiquement et ne doit pas être relancé
  manuellement.

## 9. Déployer et rétablir le workflow bloqué

- [ ] Déployer le nouveau worker orchestrateur sans supprimer les volumes Temporal ou PostgreSQL.
- [ ] Confirmer que le worker corrigé prend en charge le workflow
  `ai-factory/fbece50e/pipeline-1`.
- [ ] Laisser Temporal retenter naturellement le Workflow Task en échec.
- [ ] Ne pas envoyer un nouveau signal artificiel tant que le signal historique n'a pas été rejoué.
- [ ] Vérifier la disparition des erreurs répétées sur le run
  `01a08063-d355-756f-8e11-186c5712ab5d`.
- [ ] Vérifier que le workflow dépasse la phase `PLANNING`.
- [ ] Vérifier que la projection n'est plus signalée comme `potentiallyStale`.
- [ ] Vérifier qu'aucune seconde tâche A2A architecture n'est créée.
- [ ] Vérifier qu'un seul résultat Evidence est associé à cette étape.
- [ ] Vérifier qu'une seule progression logique vers l'étape suivante est enregistrée.

## 10. Vérifications globales après déploiement

- [ ] Rechercher les autres workflows contenant `Divergent A2A workflow notification replay`.
- [ ] Vérifier que les workflows concernés reprennent ou identifier séparément les divergences réelles.
- [ ] Contrôler le backlog de notifications A2A et les lignes `PENDING` de l'inbox.
- [ ] Contrôler les notifications tardives et les erreurs de livraison dans SigNoz.
- [ ] Vérifier l'absence d'augmentation des callbacks divergents réellement rejetés par l'inbox.
- [ ] Vérifier l'absence de duplication d'activités, d'artefacts Evidence et d'effets externes.
- [ ] Observer au moins deux fenêtres sans nouvelle erreur de replay avant de clore l'incident.

## 11. Documentation et traçabilité

- [ ] Mettre à jour `docs/operations/runbooks/A2A-CALLBACK-PERDU.md` pour préciser qu'un doublon inter-canaux est
  normal lorsque son identité métier est identique.
- [ ] Mettre à jour `docs/operations/runbooks/A2A-DIVERGENCE-ETAT.md` avec les champs définissant une divergence
  réelle.
- [ ] Documenter que `occurredAt` et la provenance ne suffisent pas à conclure à une divergence entre `GetTask` et
  callback.
- [ ] Référencer le test de régression et le test de replay dans la preuve de correction.
- [ ] Enregistrer le workflow, le run, les séquences observées et le build corrigé dans le dossier d'incident.
- [ ] Ne pas versionner de payload contenant un secret, un jeton ou une donnée non nécessaire à la preuve.

## 12. Stratégie de rollback

- [ ] Déclencher le rollback si le replay produit une erreur de non-déterminisme ou duplique une commande.
- [ ] Déclencher le rollback si des transitions réellement divergentes cessent d'être rejetées.
- [ ] Déclencher le rollback si une tâche A2A ou un effet externe est exécuté deux fois.
- [ ] Retirer le nouveau build du routage sans effacer l'historique ni les projections.
- [ ] Conserver le workflow bloqué et ses preuves pour corriger la logique ; ne pas forcer son état en base.
- [ ] Restaurer le build précédent pour les workflows compatibles, puis préparer une correction versionnée.

## Critères d'acceptation finaux

- [ ] Un doublon inter-canaux de même identité métier est accepté malgré un `occurredAt` ou des `metadata`
  différents.
- [ ] Une différence d'état, de contexte, de rôle ou d'artefacts à séquence identique reste rejetée.
- [ ] L'inbox PostgreSQL conserve sa comparaison stricte du digest des callbacks.
- [ ] Tous les tests ciblés et la suite orchestrateur réussissent.
- [ ] Le replay de l'historique réel réussit sans non-déterminisme.
- [ ] Le workflow `fbece50e` reprend sans nouvelle tâche A2A et sans effet externe dupliqué.
- [ ] La projection applicative converge de nouveau avec l'état Temporal.
- [ ] Deux fenêtres de surveillance s'écoulent sans nouvelle erreur équivalente.
- [ ] Les runbooks et la preuve de correction sont à jour.

## Définition de terminé

- [ ] Le changement de code est relu et fusionné.
- [ ] Le build corrigé est déployé et identifié dans la traçabilité Temporal.
- [ ] Le workflow incidenté a repris ou atteint un état terminal cohérent.
- [ ] Aucun doublon d'exécution, d'artefact ou d'effet externe n'a été observé.
- [ ] Les preuves de tests, de replay et de surveillance sont conservées.
- [ ] L'incident est clos avec cause racine et mesure préventive documentées.
