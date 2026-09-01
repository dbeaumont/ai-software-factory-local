# MCP-050 — Index de symboles Tree-sitter

## Résultat

`repository-context-mcp` expose `context.get_symbols` lorsque le feature flag `AI_FACTORY_CONTEXT_SYMBOLS_ENABLED` vaut `true`. L'outil recherche un symbole par chemin ou fragment de nom sans exécuter le dépôt, sans lancer de build et sans effectuer d'accès réseau.

Le serveur passe en version MCP `0.3.0`. Le binding `tree-sitter-ng` `0.26.6` et le lockset de grammaires `20260301` sont épinglés. Le descripteur retourné est donc :

```json
{"name":"tree-sitter-ng","version":"0.26.6+grammars.20260301"}
```

Grammaires embarquées :

| Langage | Extensions | Version |
|---|---|---:|
| Java | `.java` | `0.23.5` |
| Kotlin | `.kt`, `.kts` | `0.3.8.1` |
| JavaScript | `.js`, `.mjs`, `.cjs` | `0.25.0` |
| TypeScript / TSX | `.ts`, `.tsx` | `0.23.2` |
| Python | `.py` | `0.25.0` |
| Go | `.go` | `0.25.0` |

## Bornes et isolation

- la tâche, le rôle, la deadline et le commit sont validés par la primitive commune du serveur ;
- seuls les fichiers sous le workspace enregistré sont parcourus, avec les exclusions sensibles existantes ;
- un fichier source est limité à 1 MiB, un index à 1 000 fichiers et 5 000 symboles ;
- une page contient au maximum 500 symboles ; son curseur aléatoire est à usage unique et expire après 5 minutes ;
- l'index expire après 30 minutes et la mémoire conserve au plus 64 index ;
- la clé de cache contient `task_id`, `source_commit`, le nom du parseur et son lockset de versions ;
- les signatures sont bornées, privées de leur corps et leurs littéraux chaîne sont masqués.

## Activation contrôlée

Par défaut, le serveur annonce les cinq outils stables. Pour activer le sixième outil, modifier ensemble le serveur et l'allow-list stricte de l'orchestrateur :

```dotenv
AI_FACTORY_CONTEXT_SYMBOLS_ENABLED=true
AI_FACTORY_MCP_REPOSITORY_CONTEXT_ALLOWED_TOOLS=context.get_dependencies,context.get_repository_rules,context.get_symbols,context.list_tree,context.read_file,context.search_code
```

Cette activation couplée évite que la négociation de capacités accepte silencieusement un outil supplémentaire. Le retour est validé côté orchestrateur avec `context-get-symbols-runtime-v1.schema.json`, qui épingle également le parseur et son lockset.

Le runtime Java du conteneur utilise `--enable-native-access=ALL-UNNAMED`, nécessaire au chargement explicite des bibliothèques JNI avec les JDK récents.

## Vérifications

La suite couvre : activation et désactivation du feature flag, catalogue MCP à cinq ou six outils, appel HTTP MCP, toutes les grammaires épinglées, cache par commit/version, filtrage, alias de langage, pagination, curseur à usage unique, traversée de chemin, rôle interdit, divergence de commit et absence de littéral sensible dans les signatures.
