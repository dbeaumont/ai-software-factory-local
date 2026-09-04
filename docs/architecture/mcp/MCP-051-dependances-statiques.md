# MCP-051 — Lecture statique des dépendances

## Portée

`context.get_dependencies` expose les dépendances directes déclarées dans un module enregistré sur un commit immuable. Le serveur accepte un répertoire de module ou un manifeste explicite et détecte, dans cet ordre, Maven, Gradle puis npm lorsque l'écosystème vaut `UNKNOWN`.

| Écosystème | Manifeste | Déclarations lues |
|---|---|---|
| Maven | `pom.xml` | dépendances directes de `project/dependencies` ; `dependencyManagement` est exclu |
| Gradle | `build.gradle`, `build.gradle.kts` | coordonnées littérales des configurations usuelles |
| npm | `package.json` | `dependencies`, `devDependencies`, `peerDependencies`, `optionalDependencies` |

Le résultat contient le commit source, le module, l'écosystème, le nom, la version déclarée éventuelle, le scope normalisé, le caractère direct ainsi que le fichier et la ligne de déclaration. Les réponses sont bornées à 2 000 entrées et paginées par curseurs aléatoires à usage unique, liés à la tâche, au commit et au module, avec une expiration de cinq minutes.

## Garanties de sécurité

- aucun wrapper Maven/Gradle, gestionnaire npm, shell, LSP ou commande de build n'est exécuté ;
- aucune résolution transitive, interpolation distante ou récupération de métadonnées n'est effectuée ;
- le manifeste passe par la résolution de chemin centrale, les exclusions, la limite de taille et la vérification du commit ;
- le parseur XML interdit DTD, entités externes et accès aux schémas externes ;
- les expressions Gradle dynamiques ne sont pas évaluées : seules les coordonnées littérales sont retournées ;
- les versions Maven contenant une propriété restent déclaratives, par exemple `${revision}` ; elles ne sont pas résolues.

## Version et compatibilité

L'ajout de l'outil fait passer `repository-context-mcp` de `0.1.0` à `0.2.0`. L'orchestrateur épingle cette version et l'allow-list exacte des cinq outils. Le résultat est validé localement par `context-get-dependencies-runtime-v1.schema.json` avant toute utilisation.

Les tests couvrent Maven, Gradle, npm, l'exclusion de `dependencyManagement`, les scopes, la provenance, la pagination à usage unique, les manifests non supportés, les rôles refusés et la validation JSON Schema.
