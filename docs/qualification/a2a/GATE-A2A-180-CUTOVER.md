# Gate A2A-180 — Autorisation de bascule franche A2A

> Statut : `CLOSED`
>
> Candidat source : `18883d0f0e53f953a73d6e136ca56e439d1b9202`
>
> Protocole : A2A `1.0`, binding JSON-RPC, SDK Java `1.1.0.Final`
>
> Périmètre : environnement local macOS avec Docker Compose, Temporal conservé comme orchestrateur unique

## Décision demandée

Autoriser ou refuser l'exécution atomique d'A2A-181 à A2A-189. La gate reste fermée tant qu'un prérequis, une
preuve, un artefact ou une approbation est absent. Une approbation n'est valable que pour le commit et les digests
exacts inscrits dans ce document ; toute modification du candidat impose une nouvelle qualification.

Décisions admises :

- `APPROVED` : la coupure peut commencer sur les artefacts exacts ;
- `APPROVED_WITH_ACTIONS` : la gate reste fermée jusqu'à clôture vérifiée des actions ;
- `REJECTED` : aucune coupure n'est autorisée ;
- `PENDING` : aucune décision n'a encore été donnée.

## Artefacts candidats

| Artefact | Référence immuable |
|---|---|
| Runtime agent A2A | `sha256:2b65b20d09414e46b92868e2cb82a88e67330b6a57544b67b5608d047f42d4ca` |
| Orchestrateur Temporal | `sha256:ff8e04dae63cfc6937c802db470f8df39da05aade1e97099ff73de244183f556` |
| MCP Repository Context | `sha256:91b2e7f6bd8d8da2353ef8b8f63f2fc2a92af88f4639e76b577cb07f96f4e22b` |
| MCP Sandbox Execution | `sha256:345bffa6d0c971f6793327caa7e6a20b33814f3929d3cf8c967bc6d7bef1adce` |
| MCP SCM Delivery | `sha256:d46634801cdc55793f63fb435ba7a1128518b890b321b59becded4299e67a701` |
| MCP Assurance | `sha256:3485d3be8de6b767d4e9e2ffffeeade7d55ca46bfee1791149221e8a31ea6859` |
| MCP Evidence | `sha256:0bad2891a0261d2c6eb7d06b8166295787dc75c91a3f05dca1b4b3ccd1814ee2` |
| SBOM CycloneDX workspace | `sha256:bd4bd4d6ce2def99f8c359d7faf971702e9ee079531aec98d5e482b2c9c4a508` |
| SBOM SPDX workspace | `sha256:089cc13e553ff86fdd12bab5b7d3d4f0683dd11cbcc097bfe63ea2708bab1e1a` |

Les digests ci-dessus identifient les builds locaux qualifiés. Avant un déploiement dans un registre, A2A-183 doit
les remplacer ou les compléter avec les digests de manifeste du registre, les signatures Cosign et les preuves
SLSA vérifiées.

## Matrice de qualification

| Domaine | Preuve | Verdict |
|---|---|---|
| TCK officiel | [`tck/README.md`](../../evidence/a2a/tck/README.md) | `PASS` — 68 réussis, 0 échec |
| Interopérabilité | [`A2A-163-INTEROPERABILITY.md`](../../evidence/a2a/A2A-163-INTEROPERABILITY.md) | `PASS` |
| Temporal et replay | [`A2A-164-TEMPORAL-EMBEDDED.md`](../../evidence/a2a/A2A-164-TEMPORAL-EMBEDDED.md) | `PASS` — 0 erreur de replay |
| Compose et reprise | [`A2A-165-COMPOSE-INTEGRATION.log`](../../evidence/a2a/A2A-165-COMPOSE-INTEGRATION.log) | `PASS` |
| Pannes et effets uniques | [`A2A-166-FAILURE-CAMPAIGN.md`](../../evidence/a2a/A2A-166-FAILURE-CAMPAIGN.md) | `PASS` |
| Concurrence et rotation | [`A2A-167-CONCURRENCY.md`](../../evidence/a2a/A2A-167-CONCURRENCY.md) | `PASS` |
| Sécurité et isolation | [`A2A-168-SECURITY-CAMPAIGN.md`](../../evidence/a2a/A2A-168-SECURITY-CAMPAIGN.md) | `PASS` |
| Performance et saturation | [`A2A-169-PERFORMANCE.md`](../../evidence/a2a/A2A-169-PERFORMANCE.md) | `PASS` — p95 A2A = 0,60 % du budget historique |
| Parité E2E | [`A2A-170-E2E-PARITY.md`](../../evidence/a2a/A2A-170-E2E-PARITY.md) | `PASS` — 36 fixtures |
| Dépendances, images et SBOM | [`A2A-172-DEPENDENCY-AUDIT.md`](../../evidence/a2a/A2A-172-DEPENDENCY-AUDIT.md) | `PASS` |
| Rollback sans appels directs | A2A-200 à A2A-206 | `PENDING` |

## Seuils obligatoires

| Seuil | Résultat |
|---|---|
| Échecs TCK sur capacités annoncées | `0` |
| Doublons logiques ou effets externes dupliqués | `0` |
| Accès inter-tenant ou hors matrice | `0` |
| Erreurs de replay des historiques de référence | `0` |
| Divergence sur les fixtures métier | `0` |
| Budget p95 A2A | `PASS`, inférieur à 1 % de la baseline E2E |
| Scénarios d'annulation et de reprise sans preuve | `0` |
| Vulnérabilités HIGH/CRITICAL sur sources et images | `0` |

## Conditions encore bloquantes

- A2A-171 : prouver après la coupure que le mode métier `PIPELINE` n'offre aucun transport direct.
- A2A-181 à A2A-188 : exécuter la fenêtre de coupure, le smoke test et la stabilisation.
- A2A-200 à A2A-206 : qualifier le rollback vers une release A2A compatible, jamais vers les agents en mémoire.
- Remplacer les digests locaux par les digests du candidat final si une image est reconstruite.
- Obtenir les quatre décisions explicites ci-dessous sur ce candidat final.

## Sign-off

| Rôle | Identité | Décision | Date UTC | Commit examiné | Conditions / commentaire |
|---|---|---|---|---|---|
| Produit | — | `PENDING` | — | — | — |
| Architecture | — | `PENDING` | — | — | — |
| Sécurité | — | `PENDING` | — | — | — |
| Exploitation | — | `PENDING` | — | — | — |

## Effet de la gate

Le statut `CLOSED` interdit de fermer les admissions pour la bascule A2A. Une fois toutes les conditions remplies,
les quatre approbations doivent porter sur le même commit et les mêmes images, puis le statut peut devenir
`APPROVED`. Aucun statut de cette gate n'autorise une fusion de Pull Request ni un fallback vers un appel Java
direct entre agents.
