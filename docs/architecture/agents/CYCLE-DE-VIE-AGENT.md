# Cycle de vie d'un agent

Ce guide décrit le chemin obligatoire pour ajouter, modifier, évaluer, promouvoir puis retirer un agent ou un
sous-agent. Une présence dans le dépôt ne vaut jamais activation : l'hôte applique la qualification, le mode et
l'allowlist de rôles à chaque invocation.

## Artefacts gouvernés

Une version d'agent est l'ensemble immuable suivant :

- entrée dans `resources/agents/catalog-v1.yaml` ;
- manifeste `resources/agents/<role>.yaml` ;
- prompt `resources/prompts/<role>.md` ;
- schémas référencés dans `resources/multiagents/schemas/contract-catalog-v1.json` ;
- permissions dans `resources/mcp/policies/tool-permissions-v1.yaml` ;
- budgets, routage, risque et seuils de qualification applicables ;
- code hôte, tests, corpus d'évaluation et version de modèle associés.

Le rapport de qualification doit identifier les digests ou commits de cet ensemble. Modifier un seul élément
invalide le verdict précédent pour la nouvelle combinaison.

## 1. Ajouter un rôle

1. Nommer un propriétaire et justifier pourquoi un rôle existant ne couvre pas le besoin.
2. Choisir `agent` ou `sub-agent`, son parent unique, son niveau A0/A1/A2 et ses enfants éventuels.
3. Définir des contrats d'entrée et de sortie fermés, versionnés, compatibles N/N-1 et liés à la tâche, à la
   tentative, au commit et aux preuves attendues.
4. Créer le manifeste et le prompt. Le prompt décrit une responsabilité, pas une autorité supplémentaire.
5. Ajouter le rôle au catalogue avec `effectful: false` pour tout rôle LLM et l'allowlist minimale d'outils.
6. Ajouter la même allowlist, ou une plus restrictive, à la matrice MCP deny-by-default.
7. Implémenter la façade hôte et la validation qui lie rôle, parent, scope, budget et contrat.
8. Ajouter les tests de manifeste, contrat, permissions, frontières d'effet, injection et échec fermé.
9. Ajouter des cas représentatifs et adversariaux aux suites d'évaluation.
10. Laisser le rôle absent de `enabled-roles` ; l'observer d'abord en shadow via `evaluation-roles`.

Critère de revue : le diff doit rendre visible le propriétaire, les outils ajoutés, les données lues, les
contrats, les limites et le plan de retrait. Une extension d'outil sensible requiert Platform et Sécurité.

## 2. Modifier un rôle

Classer le changement avant de l'implémenter :

| Changement | Traitement |
|---|---|
| Texte du prompt sans changement de contrat | nouvelle version d'artefact et nouvelle évaluation |
| Ajout de champ optionnel | évolution additive, fixtures ancien/nouveau et compatibilité N/N-1 |
| Suppression, renommage ou nouvelle sémantique | nouveau contrat versionné et migration à deux versions |
| Outil, scope, budget ou autonomie élargi | revue Platform + Sécurité et nouveaux tests négatifs |
| Parent, enfants ou ordre de workflow modifié | nouveau graphe, tests DAG et revue de déterminisme Temporal |
| Modèle LLM ou paramètres changés | nouvelle campagne appariée et coût fournisseur complet |

Les workflows en cours restent liés à leur version. Aucun manifeste ni prompt n'est remplacé sous un identifiant
déjà utilisé par une exécution durable.

## 3. Évaluer

La qualification commence en `HIERARCHICAL_SHADOW`, sans autorité sur la décision du pipeline :

1. Exécuter les cas simples, multi-domaines, adversariaux et de reprise.
2. Comparer le même ticket et le même commit entre baseline et candidat.
3. Collecter réussite des gates, qualité du patch, incidents, routage inutile, délégations, replans,
   contradictions, interventions humaines, tokens, coût, latence et ressources.
4. Refuser le verdict si une paire, une preuve ou la télémétrie fournisseur est absente.
5. Produire un verdict `QUALIFIED`, `REJECTED` ou `INCOMPLETE` selon
   `qualification-thresholds-v1.yaml` ; ne jamais corriger manuellement une métrique pour obtenir un succès.
6. Faire approuver le rapport et l'ensemble d'artefacts exact par les propriétaires concernés.

Contrôles locaux minimaux : `AgentCatalogTest`, `AgentRoleIsolationTest`, `ToolPermissionMatrixTest`,
`MultiAgentContractValidatorTest`, `AgentActivationGuardTest`, les tests propres au rôle et
`EvaluationSuiteCoverageTest`. La suite complète de l'orchestrateur reste obligatoire avant promotion.

## 4. Promouvoir

La promotion est progressive et réversible :

1. archiver le verdict `QUALIFIED`, les approbations et les digests évalués ;
2. ajouter le rôle à `evaluation-roles` pour le shadow ;
3. après gate de qualification, ajouter explicitement le rôle à `enabled-roles` et fournir
   `qualification-verdict=QUALIFIED` ;
4. limiter le canary aux dépôts, risques et pourcentages autorisés ;
5. observer chaque palier sans erreur de contrat, dérive de coût, violation de scope ou gate contourné ;
6. promouvoir vers `HIERARCHICAL_ACTIVE` uniquement après la fenêtre et les approbations prévues ;
7. conserver `PIPELINE` et le kill switch pendant toute la période de retour arrière.

`AgentActivationGuard` vérifie le rôle et le verdict au point d'exécution. Une configuration inconnue ou
partielle échoue fermée.

## 5. Retirer ou suspendre

Une suspension urgente utilise d'abord le kill switch du rôle, puis remet sa qualification à `INCOMPLETE` et
route les nouvelles tâches vers `PIPELINE`. Elle ne supprime aucun artefact nécessaire à une reprise.

Le retrait définitif suit ensuite cette séquence :

1. interdire les nouvelles délégations vers le rôle et publier la version de catalogue correspondante ;
2. retirer le rôle des listes d'activation après vérification des tâches en cours ;
3. drainer ou terminer proprement toutes les exécutions épinglées à l'ancienne version ;
4. conserver prompt, manifeste, schémas, preuves et tests de replay pendant leur rétention ;
5. retirer les permissions devenues inutiles avant le code ;
6. supprimer le code mort et les routes de délégation dans une version ultérieure ;
7. archiver l'ADR de retrait, les métriques, incidents, approbations et la preuve de drainage.

Le rôle historique `planner` ou `reviewer` ne peut être retiré tant que des tâches `PIPELINE` ou leurs reprises
dépendent encore de son contrat de compatibilité.

## Responsabilité finale

Le propriétaire du rôle répond de sa qualité métier. Platform répond de l'isolation, de la disponibilité et de
l'application des politiques. Sécurité valide toute extension de capacité sensible. Produit et Risk valident
l'acceptabilité et l'indépendance des décisions. Exploitation contrôle le canary, le drainage et le rollback.
