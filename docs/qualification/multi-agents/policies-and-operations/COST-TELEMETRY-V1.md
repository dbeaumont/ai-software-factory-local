# Écart de télémétrie de coût fournisseur

## Constat

La campagne `MCP-agent-ab-20260902-01` contient des consommations de tokens positives mais expose
`cost_micros: 0` pour les 40 observations. Cette valeur ne prouve pas un coût nul.

Le client [`LlmGatewayClient.java`](../../../../apps/orchestrator/src/main/java/com/example/aifactory/service/LlmGatewayClient.java)
cherche `_hidden_params.response_cost`, puis `response_cost`, dans la réponse `/chat/completions`. Lorsque les deux
champs sont absents, `asDouble(0)` transforme silencieusement l'absence en zéro. Le gateway LiteLLM ne garantit
pas que ces métadonnées internes soient présentes dans la réponse OpenAI compatible utilisée par le prototype.

## Sémantique retenue

- `AVAILABLE` : chaque appel facturable possède une devise, un coût et une source de tarification traçable ;
- `PARTIAL` : une partie seulement des appels peut être valorisée ;
- `UNAVAILABLE` : aucune mesure fournisseur fiable n'est disponible ;
- zéro n'est une valeur valide que si le fournisseur l'atteste explicitement avec l'état `AVAILABLE`.

Les artefacts historiques restent immuables. Leur coût est interprété comme `UNAVAILABLE`, conformément à
[`pipeline-v1-metrics.json`](../../../../resources/multiagents/baselines/pipeline-v1-metrics.json), et non réécrit.

## Règle avant toute nouvelle comparaison

La politique
[`evaluation-data-policy-v1.yaml`](../../../../resources/multiagents/policies/evaluation-data-policy-v1.yaml) impose
une couverture de 100 % des appels facturables dans les deux variantes. Un statut `PARTIAL` ou `UNAVAILABLE`
rend le verdict global `INCOMPLETE`, même si toutes les autres métriques passent.

Une future collecte doit utiliser l'une de ces sources :

1. coût certifié retourné pour chaque appel par le gateway ;
2. journal de dépenses LiteLLM joint par identifiant d'appel stable ;
3. calcul déterministe depuis tokens d'entrée/sortie, modèle résolu, grille fournisseur versionnée et devise.

Le troisième choix doit distinguer tokens mis en cache, raisonnement, entrée et sortie lorsque la tarification les
différencie. L'alias de modèle demandé ne suffit pas : le modèle résolu et la version de grille sont conservés.

## Données minimales

Chaque observation doit porter `cost_status`, `cost_micros`, `currency`, `pricing_source`, `pricing_version`,
`provider`, `requested_model`, `resolved_model` et le nombre d'appels attendus/valorisés. La somme de valeurs
absentes n'est pas convertie en zéro.

## Dette d'implémentation

Le modèle d'exécution et `AgentAbEvaluator` utilisent encore un entier primitif pour le coût. Le lot
d'observabilité devra introduire le statut de disponibilité et faire échouer fermé l'évaluateur. Jusqu'à cette
évolution testée, aucune campagne ne peut recevoir `QUALIFIED` sur la métrique de coût.
