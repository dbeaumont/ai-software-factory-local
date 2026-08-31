# MCP-029 / MCP-030 — Observabilité du client MCP

## Journaux

Un succès journalise uniquement serveur, outil, tâche, tentative, acteur, durée, taille sérialisée et SHA-256 de la réponse. Un échec remplace les informations de réponse par un code d'erreur sûr. Aucun argument métier, contenu de réponse, stack de transport, URI ou secret n'est journalisé par ce composant.

Les identifiants sont filtrés sur une grammaire bornée avant journalisation ; une valeur inattendue devient `invalid`.

## Métriques

| Métrique | Labels bornés |
|---|---|
| `mcp_client_calls` | serveur, outil, résultat |
| `mcp_client_duration` | serveur, outil, résultat |
| `mcp_client_errors` | serveur, outil, code |
| `mcp_client_retries` | serveur, outil |
| `mcp_client_inflight` | serveur |

`task_id`, `attempt_id`, digest et message d'erreur ne sont jamais des labels. Les noms de serveur et d'outil proviennent des allowlists statiques, ce qui borne leur cardinalité.
