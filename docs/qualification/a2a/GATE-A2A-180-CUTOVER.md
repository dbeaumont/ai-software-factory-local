# Gate A2A-180 — Autorisation finale de la bascule franche A2A

> Statut : `PENDING_APPROVAL`
>
> Candidat source : `356eeebf5eab8c8d0c71884247a1789376a76d43`
>
> Protocole : A2A `1.0`, binding JSON-RPC, SDK Java `1.1.0.Final`
>
> Périmètre : environnement local macOS avec Docker Compose, Temporal conservé comme orchestrateur unique

## Décision demandée

Approuver ou refuser le candidat final déjà déployé et stabilisé. Une approbation n'est valable que pour le commit,
les images, les Build IDs et les preuves exacts inscrits dans ce document. Toute modification d'un binaire ou d'un
contrat impose une nouvelle qualification.

Décisions admises :

- `APPROVED` : la migration A2A peut être clôturée sur les artefacts exacts ;
- `APPROVED_WITH_ACTIONS` : la gate reste en attente jusqu'à la clôture vérifiée des actions ;
- `REJECTED` : les admissions doivent être fermées et le runbook de rollback A2A évalué ;
- `PENDING` : aucune décision n'a encore été donnée.

## Candidat exact

| Élément | Référence immuable |
|---|---|
| Bundle source et preuves | `356eeebf5eab8c8d0c71884247a1789376a76d43` |
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
| Révision d'admission stabilisée | `49`, `normal_operation`, ouverte |

Ces identifiants sont ceux des images locales effectivement exécutées et qualifiées. Ils ne sont pas présentés
comme des digests de manifeste d'un registre distant. Un déploiement GKE devra employer les références de registre
signées prévues par A2A-172 et A2A-226, sans changer les binaires approuvés.

## Matrice de qualification

| Domaine | Preuve | Verdict |
|---|---|---|
| TCK officiel | [`tck/README.md`](../../evidence/a2a/tck/README.md) | `PASS` — 68 réussis, 0 échec |
| Interopérabilité | [`A2A-163-INTEROPERABILITY.md`](../../evidence/a2a/A2A-163-INTEROPERABILITY.md) | `PASS` |
| Temporal et replay | [`A2A-164-TEMPORAL-EMBEDDED.md`](../../evidence/a2a/A2A-164-TEMPORAL-EMBEDDED.md) | `PASS` — 0 erreur de replay |
| Compose, persistance et reprise | [`A2A-165-COMPOSE-INTEGRATION.log`](../../evidence/a2a/A2A-165-COMPOSE-INTEGRATION.log) | `PASS` |
| Pannes et effets uniques | [`A2A-166-FAILURE-CAMPAIGN.md`](../../evidence/a2a/A2A-166-FAILURE-CAMPAIGN.md) | `PASS` |
| Concurrence et rotation | [`A2A-167-CONCURRENCY.md`](../../evidence/a2a/A2A-167-CONCURRENCY.md) | `PASS` |
| Sécurité et isolation | [`A2A-168-SECURITY-CAMPAIGN.md`](../../evidence/a2a/A2A-168-SECURITY-CAMPAIGN.md) | `PASS` |
| Performance et saturation | [`A2A-169-PERFORMANCE.md`](../../evidence/a2a/A2A-169-PERFORMANCE.md) | `PASS` — p95 A2A = 0,60 % du budget historique |
| Parité E2E | [`A2A-170-E2E-PARITY.md`](../../evidence/a2a/A2A-170-E2E-PARITY.md) | `PASS` — 36 fixtures |
| Non-régression pipeline | [`A2A-171-PIPELINE-NON-REGRESSION.md`](../../evidence/a2a/A2A-171-PIPELINE-NON-REGRESSION.md) | `PASS` |
| Dépendances, images et SBOM | [`A2A-172-DEPENDENCY-AUDIT.md`](../../evidence/a2a/A2A-172-DEPENDENCY-AUDIT.md) | `PASS` |
| Sauvegarde et restauration | [`A2A-182-CUTOVER-BACKUP.md`](../../evidence/a2a/A2A-182-CUTOVER-BACKUP.md) | `PASS` |
| Déploiement des agents | [`A2A-183-AGENT-DEPLOYMENT.md`](../../evidence/a2a/A2A-183-AGENT-DEPLOYMENT.md) | `PASS` |
| Activation Temporal | [`A2A-184-TEMPORAL-ACTIVATION.md`](../../evidence/a2a/A2A-184-TEMPORAL-ACTIVATION.md) | `PASS` |
| Suppression des appels directs | [`A2A-185-DIRECT-CALL-CUTOVER.md`](../../evidence/a2a/A2A-185-DIRECT-CALL-CUTOVER.md) | `PASS` |
| Smoke de production | [`A2A-186-PRODUCTION-SMOKE.md`](../../evidence/a2a/A2A-186-PRODUCTION-SMOKE.md) | `PASS` |
| Réouverture contrôlée | [`A2A-187-ADMISSIONS-REOPEN.md`](../../evidence/a2a/A2A-187-ADMISSIONS-REOPEN.md) | `PASS` |
| Stabilisation fail-closed | [`A2A-188-STABILIZATION.md`](../../evidence/a2a/A2A-188-STABILIZATION.md) | `PASS` — 378 s, 12 échantillons |
| Rollback exclusivement A2A | A2A-200 à A2A-206 dans le plan | `PASS` |

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
| Violations pendant la stabilisation | `0` |
| Notifications et annulations A2A en attente | `0` |
| Vulnérabilités HIGH/CRITICAL lors de l'audit qualifié | `0` |

## Conditions encore bloquantes

- obtenir les quatre décisions explicites ci-dessous sur ce candidat exact ;
- conserver les admissions ouvertes à la révision 49 jusqu'à la décision ; au premier incident, le moniteur les
  referme et invalide la demande courante ;
- après approbation, archiver le manifeste digesté A2A-227 puis clôturer l'ADR et le plan avec A2A-228.

## Sign-off

| Rôle | Identité | Décision | Date UTC | Commit examiné | Conditions / commentaire |
|---|---|---|---|---|---|
| Produit | — | `PENDING` | — | `356eeebf5eab8c8d0c71884247a1789376a76d43` | — |
| Architecture | — | `PENDING` | — | `356eeebf5eab8c8d0c71884247a1789376a76d43` | — |
| Sécurité | — | `PENDING` | — | `356eeebf5eab8c8d0c71884247a1789376a76d43` | — |
| Exploitation | — | `PENDING` | — | `356eeebf5eab8c8d0c71884247a1789376a76d43` | — |

## Effet de la gate

Le statut `PENDING_APPROVAL` conserve la plateforme A2A stabilisée, mais interdit de cocher A2A-189 et de clôturer
la migration. Les quatre approbations doivent porter sur le même candidat. Aucun statut de cette gate n'autorise
une fusion de Pull Request ni un fallback vers un appel Java direct entre agents.
