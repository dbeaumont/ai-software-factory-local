# A2A-189 — Approbation humaine de la bascule

- Décision : `APPROVED`
- Identité : David
- Date UTC : `2026-09-07T04:16:59Z`
- Candidat approuvé : `356eeebf5eab8c8d0c71884247a1789376a76d43`
- Gate : `docs/qualification/a2a/GATE-A2A-180-CUTOVER.md`

## Portée du sign-off

| Rôle | Décision |
|---|---|
| Produit | `APPROVED` |
| Architecture | `APPROVED` |
| Sécurité | `APPROVED` |
| Exploitation | `APPROVED` |

David a explicitement approuvé ces quatre rôles pour le candidat exact ci-dessus. Cette décision ne couvre aucune
modification ultérieure des sources, images, Build IDs, contrats ou preuves qualifiées.

## Références immuables approuvées

| Élément | Référence |
|---|---|
| Source runtime A2A | `eb776cd6cca1ea5724ebe0fa49577d6322f3d19c` |
| Source orchestrateur | `90057523c6e9460840743bd6032e8713b7e2a65b` |
| Runtime agent A2A, 14 rôles | `sha256:a9f616dd78e43300bfc7f92d992d9aae6a322711590a783401667c3311543ba8` |
| Orchestrateur Temporal | `sha256:a58d1600269abd9d9b0c6276129a12f29d9187d4dd0b3fedc59faaf1d4fd2ff0` |
| MCP Repository Context | `sha256:876c923401911ce02f840ac8df6705a7fe10c9bc4789b753e784e60ac3d50487` |
| MCP Sandbox Execution | `sha256:31fc6fc2f983af278490c359b54a1fe29614b2b40d6cb4bf646ea015f8e790ff` |
| MCP SCM Delivery | `sha256:22fcf004deef72b20b364e4cb28eba3a05d64f045b1357b7b76c5ed8f50531d6` |
| MCP Assurance | `sha256:7ec65436b33b5c3c15a751656c1aebfc692268ac5081e93bc78ed52e9030e0ff` |
| MCP Evidence | `sha256:b0acb4af07773eb92a00c89003b88344f217d5ae7f86b0cf06e34d625d3617ad` |
| Build ID Temporal A2A | `a2a-agent-eb776cd` |
| Build ID Temporal orchestrateur | `a2a-cutover-d4b55c7` |
| Révision d'admission | `49`, ouverte avec la raison `normal_operation` |

## Vérification au moment de l'approbation

- la matrice de qualification de la gate est intégralement `PASS` ;
- la fenêtre de stabilisation couvre 378 secondes et 12 échantillons sans violation ;
- `make a2a-smoke` a été rejoué avec succès le 2026-09-07 avant l'enregistrement du sign-off ;
- OAuth2 client credentials, JWT et mTLS sont actifs ;
- les Agent Cards et runtimes des 14 rôles sont valides et sains ;
- aucun fallback vers les appels Java directs n'est autorisé.
