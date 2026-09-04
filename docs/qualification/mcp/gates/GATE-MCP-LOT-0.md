# Gate du lot 0 — Cadrage MCP

> Statut : `APPROVED`
>
> Coordinateur : **David Beaumont**
>
> Commit de consolidation : `2dd5442e83a0241d33b78472dc1e9e94085b339f`

## 1. Décision demandée

Le `Product Owner AI Software Factory` doit décider si le cadrage MCP-000 à MCP-017 autorise la poursuite du plan dans le périmètre suivant :

- développement et tests du **POC local isolé** ;
- correction des écarts de contrats, observabilité et isolation déjà recensés ;
- activation progressive `DIRECT -> MCP_SHADOW -> MCP_ACTIVE` uniquement après les tests prévus ;
- aucune ouverture à un environnement partagé ou de production par ce gate.

Décisions possibles :

- `APPROVED` : poursuite du POC local dans les limites ci-dessous ;
- `APPROVED_WITH_ACTIONS` : poursuite conditionnée par des actions Produit supplémentaires, datées ;
- `REJECTED` : aucune extension du périmètre MCP avant nouveau passage du gate.

Une absence de décision conserve le gate fermé.

## 2. État des livrables

| Bloc | Tâches | État |
|---|---|---|
| responsabilités et architecture | MCP-000 à MCP-004 | terminé |
| menace, SDK et versions | MCP-005 à MCP-008 | terminé |
| schémas, permissions et limites | MCP-009 à MCP-012 | terminé, contrats cibles avec écarts runtime suivis |
| baseline positive et cas négatifs | MCP-013 à MCP-014 | terminé |
| SLO et rollback | MCP-015 à MCP-016 | terminé, instrumentation complète à venir |
| revue sécurité/exploitation | MCP-017 | `APPROVED` par les deux responsables |

Preuves principales :

- baseline `customer-api` arrêtée à `WAITING_APPROVAL`, sans commit, push ni Pull Request ;
- six cas négatifs fail-closed ;
- politiques machine-readable de permissions, limites, redaction, SLO et rollback ;
- pré-revue technique avec huit écarts reliés à des tâches de remédiation ;
- suites de tests orchestrateur et sandbox vertes lors des campagnes documentées.

## 3. Restrictions non négociables

Une décision `APPROVED` sur ce gate n'autorise pas :

1. l'exposition des endpoints MCP hors des réseaux privés du POC ;
2. un déploiement partagé ou de production ;
3. une campagne réelle de retry SCM ou une livraison sans approbation humaine ;
4. l'activation de la validation stricte des contrats avant correction de `TECH-02` à `TECH-04` ;
5. l'accès direct d'un agent à un serveur MCP, un backend, un secret ou un outil à effet ;
6. le retour de la socket Docker ou des secrets sandbox dans l'orchestrateur ;
7. l'assimilation d'une preuve absente, partielle ou altérée à un succès ;
8. le contournement des gates par fallback direct pendant un incident MCP.

Ces restrictions restent applicables même si le budget SLO est disponible.

## 4. Plan de fermeture des écarts

| Écart | Propriétaire de suivi | Échéance de contrôle | Tâches |
|---|---|---|---|
| authentification workload absente | Sécurité + plateforme | avant tout environnement partagé | MCP-210 à MCP-213 |
| enveloppe sans `attempt_id`/deadline | Équipe AI Software Factory | avant clôture du gate lot 1 | MCP-026, MCP-033 |
| schéma sandbox incomplet | Équipe AI Software Factory | avant activation de MCP-025 | MCP-025, MCP-033 |
| catalogue incomplet | Équipe AI Software Factory | avant négociation autoritative | MCP-024, MCP-025 |
| livraison Gitea non isolée/idempotente | Produit + sécurité | avant toute campagne réelle de retry/livraison MCP | MCP-110 à MCP-120 |
| SLO non totalement instrumentés | Exploitation | avant déclaration de SLO supervisé | MCP-030, MCP-222 |
| contrôleur Docker POC-only | Plateforme | avant cible GCP | MCP-092, MCP-214, MCP-215 |
| preuves mutables | Sécurité + équipe preuves | avant environnement partagé | MCP-145 à MCP-149 |

## 5. Sign-off du gate

| Rôle | Décision | Date | Commit examiné | Commentaire |
|---|---|---|---|---|
| Coordinateur — David Beaumont | `PREPARED` | 2026-08-31 | `2dd5442e83a0241d33b78472dc1e9e94085b339f` | dossier consolidé |
| Sécurité — Représentant RSSI | `APPROVED` | 2026-08-31 | `13ebb632f7be925dfac0b6b75ada1ae178543ac5` | restrictions techniques maintenues |
| Exploitation — Responsable Exploitation | `APPROVED` | 2026-08-31 | `13ebb632f7be925dfac0b6b75ada1ae178543ac5` | restrictions techniques maintenues |
| Produit — Product Owner AI Software Factory | `APPROVED` | 2026-08-31 | `2dd5442e83a0241d33b78472dc1e9e94085b339f` | poursuite du POC local dans le périmètre du gate |

## 6. Effet de la décision `APPROVED`

La décision Produit produit les effets suivants :

1. le gate du lot 0 est coché dans `TODO-MCP.md` avec la décision et le commit ;
2. la prochaine tâche séquentielle devient MCP-021 ;
3. `TECH-02` à `TECH-04` sont traités avant toute validation stricte ou négociation autoritative ;
4. les autres écarts restent bloquants à leur frontière indiquée.

Une décision Produit ne vaut ni promotion vers un environnement partagé, ni validation des gates des lots suivants.
