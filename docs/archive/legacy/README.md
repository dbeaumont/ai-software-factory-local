# Archives documentaires

Ce répertoire conserve les études, schémas et cibles remplacés. Leur présence sert à comprendre les décisions
historiques ; ces fichiers ne décrivent pas l'état courant du prototype et ne doivent pas guider un déploiement.

## Documents archivés

| Document | Motif | Référence actuelle |
|---|---|---|
| `architecture-cible-mcp-ai-software-factory.pdf` | étude de la transition MCP antérieure à l'architecture 04 | `../version-1.1.0-archi-02-mcp/` puis `../version-1.2.0-archi-04/` |
| `cible-architecture-cible-gcp-ai-software-factory.md` | cible GCP pré-multi-agent | `../version-1.2.0-archi-04/cible-architecture-multi-agent-hierarchique.md` |
| `cible-industrialisation avec GCP.md` | analyse d'industrialisation fondée sur l'ancien pipeline | architecture 1.2.0 et ADR MAH |
| `ia software factory - archi technique - cible.png.png` | schéma statique historique | diagrammes Mermaid de l'architecture 1.2.0 |
| `ia software factory - archi technique - poc.png` | schéma du POC historique | états versionnés 1.0.0 et 1.1.0 |
| `ia software factory - fonctionnement - cible.png` | workflow cible historique | état du prototype 1.2.0 |
| `ia software factory - fonctionnement- poc.png` | workflow du POC historique | états versionnés 1.0.0 et 1.1.0 |
| `modernisation-ghc.md` | étude ponctuelle non normative | aucune |

## Historique conservé

Les déplacements et suppressions ont été réalisés par commits dédiés. Git conserve le contenu et permet de
retrouver son origine avec `git log --follow -- <chemin>` puis `git show <commit>:<ancien-chemin>`. Les documents
d'architecture 02 restent directement consultables dans `docs/version-1.0.0-archi-02-sans-MCP/` et
`docs/version-1.1.0-archi-02-mcp/` ; ils sont versionnés, pas supprimés.

Le doublon racine `docs/cible-architecture-multi-agent-hierarchique.md` a été retiré : sa copie canonique et son
historique utile sont conservés dans `docs/archive/releases/1.2.0-archi-04/` et dans Git.
