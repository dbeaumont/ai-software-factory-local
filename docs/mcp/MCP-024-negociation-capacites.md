# MCP-024 — Négociation des capacités MCP

## Objectif

L'orchestrateur ne considère un serveur MCP comme utilisable qu'après comparaison de son contrat annoncé avec un contrat local épinglé. La négociation est exécutée au démarrage de l'application et son résultat alimente les indicateurs Actuator par serveur.

## Contrat attendu

Pour chaque connexion statique, `McpClientProperties` fixe :

- les versions du protocole MCP acceptées globalement ;
- le nom exact et la version sémantique attendus du serveur ;
- l'ensemble exact des outils autorisés.

Les valeurs initiales sont :

| Serveur | Version | Outils autorisés |
|---|---:|---|
| `repository-context-mcp` | `0.1.0` | `context.get_repository_rules`, `context.list_tree`, `context.read_file`, `context.search_code` |
| `sandbox-execution-mcp` | `0.1.0` | `sandbox.validate_patch`, `sandbox.apply_patch`, `sandbox.run_tests`, `sandbox.run_quality`, `sandbox.run_security`, `sandbox.get_execution`, `sandbox.cancel_execution` |

La version de protocole initialement acceptée est `2025-06-18`. Une modification de cette liste, d'une version serveur ou d'une allowlist est un changement de configuration contrôlé.

## Négociation

`SpringMcpToolInvoker` masque les types du SDK et retourne un descripteur neutre contenant la version du protocole, l'identité du serveur, sa version et les noms d'outils. Le catalogue est paginé avec une borne dure de seize pages.

`McpServerRegistry` compare le descripteur au contrat local. La comparaison des outils est stricte : un outil absent **ou supplémentaire** rend le serveur incompatible. Cette règle empêche qu'un serveur compromis ou mal configuré injecte silencieusement un nouvel outil.

## États de santé

| État | Condition | Effet en mode actif |
|---|---|---|
| `READY` | protocole, identité, version et outils correspondent exactement | indicateur `UP` |
| `DEGRADED` | client désactivé, non initialisé ou serveur inaccessible | indicateur `DOWN` |
| `INCOMPATIBLE` | divergence de protocole, identité, version ou catalogue | indicateur `DOWN` |

Dans un mode non actif (`DIRECT` ou `MCP_SHADOW`), l'indicateur reste `UP` pour préserver le chemin historique mais expose l'état dégradé ou incompatible dans ses détails. Aucun secret, URI ou contenu de réponse n'est publié.

## Vérification

`McpServerRegistryTest` couvre le contrat exact, l'indisponibilité, les versions incompatibles, l'identité inattendue, l'outil absent et l'outil supplémentaire. Les tests des health indicators vérifient le comportement fail-closed en mode actif et non bloquant en shadow.
