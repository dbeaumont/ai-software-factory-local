# A2A-186 — Smoke test de production

Date : 2026-09-07

## Verdict

- **Statut : réussi**
- **Source runtime déployée :** `8693e81e0a291b39e2b08ae185578722692759db`
- **Source de qualification :** `30083f22e13907f22100c4bf638ff95ce184f9f2`
- **Image commune des 14 agents en exécution :**
  `sha256:bee6f34f19d0e8e39e32ab5a0b460733d2ab30a43e1c672f0e39792f51bc1c37`
- **Image orchestrateur en exécution :**
  `sha256:051917eae8406e81c4443c104fc0da2bb9c416924b0a09ee76a767f5cc3ce9d0`
- **Admissions pendant la qualification :** fermées, révision `46` ; le smoke de livraison ne les a ouvertes
  que pour son unique soumission, puis les a refermées.

## Supervisor et chemin hiérarchique

`make a2a-smoke` a validé depuis l'identité mTLS de l'orchestrateur la disponibilité, la signature et le rôle de
chacune des 14 Agent Cards. Le runtime `a2a-supervisor` était `healthy` et sa carte était `VALID`.

`make test-a2a-e2e-parity` a ensuite rejoué les 36 cas métier après rescèlement des contrats ajoutés par A2A-171 :

- 10 chemins `HIERARCHICAL_PATH` conservent l'ordre Supervisor → Architecture → Code → Tests → Security →
  Independent Reviewer ;
- 8 chemins `SHORT_CODE_PATH`, 2 passages en `HUMAN_TRIAGE` et les 16 scénarios de refus ou récupération
  conservent leur verdict ;
- 20 contrats dorés et 14 sorties primaires ont été validés ;
- les tests Temporal du DAG, des dépendances, des pannes et des annulations sont verts.

Cette vérification du chemin hiérarchique n'a créé aucune admission métier pendant la fermeture globale.

## Livraison réelle Temporal → A2A

La commande `TEMPORAL_CUTOVER_SMOKE=true make test-temporal-pipeline-delivery` a terminé un parcours de livraison
réel :

| Élément | Valeur |
|---|---|
| Ticket | `AF-0145` / tâche `29004b4c` |
| Run Temporal racine | `01a079be-b0b1-7478-981b-156767acc238` |
| Commit source traité | `dba6024e36a402de63a121d52f24c70c33b44308` |
| Manifeste d'approbation | `bcd53b1b34975fe8e6fa1e1e6167bdcb72fc55bd1a39211a07ca6f8e65edc184` |
| Digest du manifeste | `79b05fc756d1e717d7f4f0061a26fc3b6cd8ad604d5b450121d6d5a245893a00` |
| Résultat | `PR_CREATED`, une seule PR, puis PR/branche/workspace de smoke supprimés |

Les cinq tâches A2A corrélées au ticket sont terminales `COMPLETED` : Architecture, Developer, Patch Repair,
Tests et Independent Reviewer. Les artefacts Plan, Patch, Tests, Quality, Security, SBOM et Review sont tous
`COMPLETE` dans Evidence. La revue indépendante a retourné `ACCEPT`; l'effet SCM n'a été exécuté qu'après le
signal d'approbation lié à l'identifiant et au digest exacts du manifeste. Le second signal identique n'a créé
aucune PR supplémentaire.

## Annulation, panne et récupération des preuves

Les deux campagnes Compose isolées ont été rejouées sur le candidat :

- `make test-a2a-compose-integration` : envoi idempotent, continuation, redémarrage durable, résultat et historique
  conservés, callback perdu récupéré par polling, annulation terminale `TASK_STATE_CANCELED` ;
- `make test-a2a-compose-failures` : indisponibilités agent, PostgreSQL et réseau privé en mode fermé puis
  récupération, avec un seul enregistrement durable après rejeu ; les pannes Temporal, LLM, MCP et Evidence sont
  couvertes par la suite de défaillance du runtime.

Digests des sorties rejouées :

| Preuve | SHA-256 |
|---|---|
| `A2A-165-COMPOSE-INTEGRATION.log` | `9fbe3e7067f939a31e164b2fc3036f078b37c5cba0648016b488ace265dccde8` |
| `A2A-166-FAILURE-CAMPAIGN.log` | `b8e4765736b076ac4271b58543f9adb5bb2048e16f5008ded870bb67f8001526` |
| `A2A-170-E2E-PARITY.md` | `bc2eee59e9ddeefa631e4a8b3c7440e7f340258adb2a08fa896f6ddc2e9439ad` |

La perte de callback n'est pas interprétée comme une perte de tâche ou de preuve : Temporal réconcilie l'état
terminal par `tasks/get`, et Evidence reste immuable et adressé par digest.

## Commandes validées

```shell
make a2a-smoke
make test-a2a-e2e-parity
make test-a2a-compose-integration
make test-a2a-compose-failures
TEMPORAL_CUTOVER_SMOKE=true make test-temporal-pipeline-delivery
```

Les admissions peuvent être rouvertes par A2A-187 après un dernier contrôle des cartes, files Temporal,
runtimes et dépendances obligatoires. Le backlog de notifications est traité séparément pendant A2A-188 : les
preuves durables et la réconciliation par polling ont déjà empêché toute perte fonctionnelle pendant ce smoke.
