# Politique des données persistées dans Temporal

## Périmètre

Cette politique couvre les entrées, résultats, signaux, memo et Search Attributes conservés dans les historiques
Temporal. Elle est appliquée avant tout appel au client SDK par `TemporalPayloadGuard`.

## Données autorisées

- identifiants bornés de tâche, tentative, workflow, activité et délégation ;
- commit source, digests SHA-256, statuts, décisions et budgets bornés ;
- URI `evidence://` liées à la tâche et métadonnées d'artefacts ;
- noms de repository, branche, rôle, opération, task queue et version appartenant aux ensembles contrôlés ;
- identité d'un acteur nécessaire à l'audit des commandes humaines.

Le besoin métier, l'objectif d'une délégation et la question d'une décision humaine sont transformés en digest avant
sérialisation. Le contenu demeure dans la projection Evidence, hors de l'historique Temporal.

## Données interdites

- secrets, credentials, clés privées, authorization headers et cookies ;
- patch ou code source complet ;
- sortie de commande, trace ou log volumineux ;
- contenu marqué `CONFIDENTIAL`, `RESTRICTED` ou `SECRET` ;
- champ textuel de plus de 4 096 caractères, payload de plus de 64 KiB, collection non bornée ;
- memo, description statique ou Search Attribute non explicitement autorisé et versionné.

Les activités génériques qui acceptaient une invocation agent ou MCP contenant du texte brut ne sont plus enregistrées
sur les workers. Les activités de production enregistrées échangent des commandes à digests et des références Evidence.

## Comportement en cas d'écart

Le démarrage ou le signal est refusé avant l'appel réseau avec une `SecurityException` sans recopier la valeur fautive
dans le message ou les logs. Les tests `TemporalPayloadGuardTest` et `TemporalWorkerRegistryTest` verrouillent cette
frontière. Toute extension de payload ou de Search Attributes doit mettre à jour cette politique et ses tests.
