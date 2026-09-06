# Gate TEMP-103 — Autorisation de coupure Temporal

> Statut : `APPROVED`
>
> Release examinée : `9698fa30aa16a43af5b2d4f56b82f1953eb95080`
>
> Image orchestrateur : `sha256:ef7b416ef628b3629e21d597b62be85aedf480594f530d814635baf4442d604e`
>
> Périmètre : environnement local macOS avec Docker Compose, bascule franche sans canary ni fallback local

## Décision demandée

Autoriser ou refuser la fermeture des admissions et l'exécution atomique de TEMP-104 à TEMP-111. Une absence de
décision, un commit examiné différent ou une condition non satisfaite conserve le gate fermé.

Décisions admises :

- `APPROVED` : le rôle autorise la coupure sur la release exacte ci-dessus ;
- `APPROVED_WITH_ACTIONS` : le gate reste fermé jusqu'à preuve de réalisation de chaque action ;
- `REJECTED` : aucune coupure n'est autorisée.

## Matrice de preuves soumise

| Domaine | Preuve | Résultat |
|---|---|---|
| Fonctionnel | Pipeline complet jusqu'à une PR Gitea propre au ticket | `PASS` — tâche `054a0420`, PR `#5` |
| Architecture | Replay, déterminisme, 7 task queues et 8 phases de redémarrage | `PASS` |
| Sécurité | Tests propres, Trivy/SBOM du ticket, isolation réseau, admission fail-closed | `PASS` |
| Exploitation | Backpressure, partition, pannes de dépendances, sauvegarde/restauration | `PASS` |
| Reprise | Heartbeat sans double soumission, stockage, rotation de worker, cycle Compose | `PASS` |
| Artefact | Image inchangée pendant les 16 étapes de qualification | `PASS` |

Preuve détaillée :
[`TEMP-102-qualification-release-2026-09-06.md`](../../evidence/temporal/TEMP-102-qualification-release-2026-09-06.md).

## Points à accepter explicitement

- Temporal devient immédiatement l'unique autorité d'orchestration de toutes les nouvelles admissions.
- Le coordinateur local et ses fallbacks sont supprimés ; un rollback restaure une version worker compatible,
  jamais l'ancien moteur.
- La fermeture des admissions dure jusqu'au smoke test post-déploiement réussi.
- Les volumes Temporal, projection, Evidence et SCM restent préservés pendant toute la fenêtre.
- Les PR de qualification ne sont pas fusionnées automatiquement.

## Sign-off

Chaque ligne doit nommer la personne ou l'autorité, porter une décision, une date UTC et le commit exact examiné.
Les quatre décisions doivent être `APPROVED` avant de cocher TEMP-103.

| Rôle | Identité | Décision | Date UTC | Commit examiné | Conditions / commentaire |
|---|---|---|---|---|---|
| Produit | David Beaumont | `APPROVED` | 2026-09-06 | `9698fa30aa16a43af5b2d4f56b82f1953eb95080` | Validation explicite de la release pour le POC local. |
| Architecture | David Beaumont | `APPROVED` | 2026-09-06 | `9698fa30aa16a43af5b2d4f56b82f1953eb95080` | Validation explicite de la bascule franche vers Temporal. |
| Sécurité | David Beaumont | `APPROVED` | 2026-09-06 | `9698fa30aa16a43af5b2d4f56b82f1953eb95080` | Validation explicite des preuves de sécurité de la release. |
| Exploitation | David Beaumont | `APPROVED` | 2026-09-06 | `9698fa30aa16a43af5b2d4f56b82f1953eb95080` | Validation explicite de l'ouverture de la fenêtre de coupure. |

Dans le périmètre de ce POC local, David Beaumont porte les quatre responsabilités ci-dessus. Cette délégation
n'est pas transposable telle quelle à un environnement partagé ou de production.

## Effet du gate

Le gate est ouvert uniquement si les quatre lignes sont approuvées sur
`9698fa30aa16a43af5b2d4f56b82f1953eb95080`. L'ouverture autorise TEMP-104 ; elle ne dispense d'aucun contrôle de
la fenêtre de coupure et ne permet pas de fusionner une Pull Request.
