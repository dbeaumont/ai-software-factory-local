# MCP-021 — Configuration du client MCP

## 1. Structure

`McpClientProperties` est lié au préfixe `ai-factory.mcp.client`. Il centralise les paramètres de contrôle du client sans remplacer les feature flags de migration portés par `McpFactoryProperties`.

| Niveau | Paramètres |
|---|---|
| global | activation du client, timeout par défaut, taille maximale de réponse, concurrence par serveur et par tâche |
| retry lecture | tentatives, backoff initial/maximal, multiplicateur et jitter |
| retry effet | mêmes bornes, avec un maximum dur de deux tentatives |
| serveur | activation, URI statique, audience attendue et timeout spécifique |

Les serveurs initiaux sont `repository-context` et `sandbox-execution`. Leur URI provient exclusivement de la configuration de déploiement ; aucune valeur issue d'un ticket, dépôt, prompt ou résultat de modèle ne peut la remplacer.

## 2. Valeurs initiales

| Paramètre | Valeur |
|---|---:|
| timeout global | 20 s |
| taille de réponse | 65 536 octets |
| appels simultanés par serveur | 16 |
| appels simultanés par tâche | 4 |
| retry lecture | 3 tentatives, 200 ms à 2 s, multiplicateur 2, jitter 0,2 |
| retry à effet | 2 tentatives, 500 ms à 2 s, multiplicateur 2, jitter 0,2 |

Ces valeurs reproduisent `default-limits-v1.yaml`. La déclaration de MCP-021 ne déclenche pas encore les retries : leur application sélective selon le code d'erreur et l'idempotence appartient à MCP-027/MCP-028.

## 3. Validations fail-fast

Le démarrage est refusé si :

- aucun serveur n'est déclaré ;
- une URI n'utilise pas HTTP(S), n'a pas d'autorité, contient des credentials, une query ou un fragment ;
- une audience est vide ;
- un timeout ou backoff est nul/négatif ;
- la concurrence par tâche dépasse celle du serveur ;
- la réponse maximale dépasse 1 Mio ;
- le jitter sort de `[0, 1]`, le multiplicateur de `[1, 10]` ou le retry à effet dépasse deux tentatives.

Ces validations protègent la configuration locale. L'allow-list autoritative des serveurs et la négociation de leurs capacités restent traitées par MCP-022/MCP-024.

## 4. Articulation avec Spring AI

Les connexions Spring AI restent déclarées statiquement dans `spring.ai.mcp.client.streamable-http.connections`. Elles utilisent les mêmes variables d'environnement d'URI et de timeout que `McpClientProperties`, ce qui évite deux sources divergentes pendant la migration.

Les audiences sont enregistrées dès maintenant pour préparer OAuth/workload identity, mais aucun header d'identité n'est généré tant que MCP-210 à MCP-213 ne sont pas implémentés.

## 5. Feature flags

- `AI_FACTORY_MCP_CLIENT_ENABLED` active l'infrastructure client Spring AI et la configuration globale.
- `AI_FACTORY_MCP_ENABLED` active la capacité de contexte dépôt.
- `AI_FACTORY_MCP_SANDBOX_ENABLED` active la capacité sandbox.
- Les modes `DIRECT`, `MCP_SHADOW` et `MCP_ACTIVE` restent indépendants des flags d'activation.

Un mode `MCP_ACTIVE` avec serveur désactivé continue d'échouer fermé ; il ne déclenche aucun fallback direct implicite.

## 6. Vérification

`McpClientPropertiesTest` vérifie :

- le binding de deux serveurs, audiences, limites, timeouts et retries ;
- le refus d'une URI contenant des credentials ;
- le refus d'une concurrence par tâche supérieure à la limite du serveur.
