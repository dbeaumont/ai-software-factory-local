# MCP-026 — Contexte distribué des appels MCP

## Enveloppe propagée

Chaque opération MCP construit une enveloppe immuable, réutilisée pour tous ses appels et toutes ses pages :

| Champ | Rôle |
|---|---|
| `task_id` | rattachement à la tâche de l'usine |
| `attempt_id` | identifiant aléatoire stable de l'opération courante |
| `actor` | rôle autorisé, actuellement `workflow` |
| `trace_id` | identifiant de trace historique sur 32 caractères hexadécimaux |
| `traceparent` | contexte W3C `00-<trace-id>-<span-id>-01` cohérent avec `trace_id` |
| `deadline` | échéance RFC 3339 calculée par le client |

`McpRequestMetadata` génère les identifiants avec `SecureRandom`. Le contexte dépôt dispose d'une échéance de 20 secondes ; le sandbox conserve une même échéance jusqu'à la fin de sa fenêtre de polling.

## Validation serveur

Les deux serveurs exposent ces paramètres dans le schéma d'entrée de tous leurs outils. Ils refusent :

- un `attempt_id` invalide ;
- un `traceparent` mal formé ou dont le trace ID diffère de `trace_id` ;
- une deadline expirée, invalide ou située à plus de 24 heures.

Le champ historique `trace_id` est conservé durant la migration pour la compatibilité des journaux existants. `traceparent` devient la valeur de propagation distribuée de référence.

## Vérification

Les tests unitaires de l'orchestrateur contrôlent la présence et la cohérence de l'enveloppe. Les tests d'intégration des deux serveurs vérifient que les nouveaux paramètres sont publiés et acceptés par les outils MCP.
