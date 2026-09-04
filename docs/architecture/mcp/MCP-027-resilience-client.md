# MCP-027 — Résilience du client MCP

## Chaîne d'appel

`ResilientMcpToolInvoker` est le point d'entrée primaire de l'orchestrateur. Il entoure le client validé et applique circuit breaker, limites de concurrence, timeout, validation de réponse, classification de l'erreur et retry éventuel.

## Politique

- le timeout effectif est le minimum entre le timeout du serveur et la deadline initiale ;
- la concurrence est bornée globalement par serveur et par couple serveur/tâche ;
- un appel sous-jacent ayant dépassé son timeout conserve son permit jusqu'à son arrêt effectif ;
- seules les erreurs `DEPENDENCY_UNAVAILABLE` et `TIMEOUT` sont retryables ;
- les erreurs de validation, politique, limite, argument ou outil ne sont jamais retentées ;
- les opérations à effet utilisent la politique courte et ne sont retentées que si une clé d'idempotence est présente ;
- le backoff est exponentiel, borné et affecté du jitter configuré ;
- après cinq appels terminaux sur erreur retryable, le circuit s'ouvre durant 30 secondes.

Le compteur de tentatives inclut l'appel initial. La deadline d'origine n'est jamais prolongée.

## Tests

`ResilientMcpToolInvokerTest` couvre retry sélectif, absence de retry d'une réponse malformée, timeout, conservation du permit après timeout, ouverture du circuit et métriques.
