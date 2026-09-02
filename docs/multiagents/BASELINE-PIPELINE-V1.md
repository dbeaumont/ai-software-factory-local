# Baseline reproductible du pipeline V1

La référence de comparaison de la migration est le tag `v1.1.0-mcp`, commit
`45e72011a8cc2c81006a5ff7b8b3a3f725db5174`. Le commit de départ de la branche de migration
`99239c613d88a5112a6d14e92e61bda81ac9daac` possède le même contenu exécutable ; seules des documentations et
le `.gitignore` diffèrent.

Le manifeste
[`resources/multiagents/baselines/pipeline-v1.yaml`](../../resources/multiagents/baselines/pipeline-v1.yaml)
fige les objets Git des prompts, rôles, politiques, schémas, infrastructure et composants Java. Il enregistre
également le modèle configuré, les versions d'outillage, les digests d'images observés et les artefacts de la
campagne MCP-180. Aucun secret ni valeur locale de `.env` n'y est copié.

## Vérification

Depuis la racine du dépôt :

```shell
ruby scripts/verify-pipeline-baseline.rb
```

Le contrôle échoue si le tag, un objet Git ou un artefact de campagne ne correspond plus au manifeste. Les
digests des images construites localement constituent des identifiants de contenu de l'environnement figé ; les
images tierces utilisent des références par digest récupérables depuis leur registre.

## Rejeu

1. créer un worktree ou un checkout détaché sur `v1.1.0-mcp` ;
2. dériver la configuration locale de `.env.example` sans committer de secret ;
3. utiliser les références d'images du manifeste, ou vérifier les images locales par leur digest de contenu ;
4. exécuter `make bootstrap`, puis `make mcp-agent-ab-campaign` avec les autorisations fournisseur attendues ;
5. conserver les sorties JSONL avec leur SHA-256 et les paramètres effectifs du fournisseur.

Une réponse LLM n'est pas reproductible bit à bit. La reproductibilité recherchée porte donc sur le code, les
prompts, les politiques, les modèles configurés, les images et les cas appariés ; la comparaison porte sur des
métriques et seuils, pas sur l'identité textuelle des réponses.
