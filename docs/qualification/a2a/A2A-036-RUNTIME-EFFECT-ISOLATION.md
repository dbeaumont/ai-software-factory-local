# A2A-036 — preuve d'isolation des effets du runtime agent

Le runtime `a2a-agent-runtime` est un processus de calcul non effectful. Son graphe de dépendances, sa configuration
et son image ne lui donnent aucun chemin direct vers le SCM, le daemon Docker, Temporal, PostgreSQL ou les
projections applicatives.

| Frontière | Contrôle exécutable |
|---|---|
| outils métier | chaque rôle agent est limité à `context.*`, `evidence.get_summary` et `evidence.read` |
| MCP | seules les URL Context et Evidence existent dans la configuration du runtime |
| SCM | SDK JGit interdit par Maven Enforcer ; aucun endpoint ni secret SCM |
| Docker | client Docker interdit ; aucun montage de socket dans l'image |
| Temporal | SDK Temporal interdit ; le runtime n'est pas un worker du contrôle-plane |
| données/projections | JDBC, JPA, PostgreSQL et Flyway interdits ; auto-configurations datasource exclues |
| artefact | seul le JAR `a2a-agent-runtime` est copié dans l'image finale non-root |

La classe `AgentRuntimeEffectIsolationTest` relit les sources, le POM, la configuration, le Dockerfile et les
quatorze rôles à chaque build. Maven Enforcer examine aussi les dépendances transitives et fait échouer le build si
un des clients interdits réapparaît.
