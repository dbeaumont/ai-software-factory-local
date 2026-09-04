# MCP-041 à MCP-053 — Durcissement du contexte dépôt

## Registre des workspaces

`TaskWorkspaceRegistry` persiste la relation exacte `task_id → chemin réel → source_commit` dans un volume dédié. Le premier accès enregistre atomiquement la relation après vérification Git ; tout changement ultérieur de racine ou de commit pour le même identifiant échoue fermé. Le workspace reste monté en lecture seule dans le serveur MCP.

## Outils

- `context.list_tree` accepte au plus 32 filtres glob bornés, retourne au plus 1 000 entrées par page et 5 000 au total, et utilise des curseurs aléatoires à usage unique expirant après cinq minutes.
- `context.search_code` reste strictement littéral, contrôle sa deadline pendant le parcours et borne fichiers, occurrences et extraits.
- `context.read_file` refuse les fichiers trop grands, binaires ou non UTF-8, applique les plages/l'enveloppe en octets, et retourne un MIME déterministe et le SHA-256 du fichier source.
- `context.get_repository_rules` retourne une provenance `repo://`, un ordre d'applicabilité explicite et du contenu toujours considéré comme donnée non fiable.

## Ressources immuables

Le template `repo://{task_id}/{source_commit}/{path}` expose un fichier borné et redacted. Les séparateurs du chemin sont encodés dans la variable `path`. Chaque lecture revérifie le registre, le commit Git réel, la politique de chemin et le type texte. Le serveur publie la capacité `resources` conformément aux annotations serveur Spring AI.

## Redaction et tests négatifs

La redaction analyse les clés de configuration au lieu de rechercher naïvement une sous-chaîne : `tokenizer` et `secretary` restent lisibles, tandis que les suffixes `password`, `secret`, `token`, `api-key` et `private-key` sont masqués.

La suite teste traversal brut/encodé/absolu, symlink sortant, taille, binaire, secrets, motif de regex traité littéralement, deadline, commit divergent, pagination/cursor et lectures concurrentes de deux tâches sans croisement.

Référence d'implémentation des ressources : [documentation officielle Spring AI sur `@McpResource`](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html).
