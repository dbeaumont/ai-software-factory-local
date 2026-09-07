# A2A-170 — Parité E2E métier

Date : 2026-09-06

## Verdict

- **Statut : réussi**
- **Commande :** `make test-a2a-e2e-parity`
- **Baseline figée :** `a2a-pre-cutover-functional-v1`
- **Résultat Java :** 16 suites, 59 tests, 0 échec, 0 erreur, 0 test ignoré.

## Parité fonctionnelle

La campagne vérifie les digests SHA-256 des preuves de référence avant de rejouer les quatre corpus métier. Les
36 cas conservent exactement leurs verdicts, leurs gates humaines et leur ordre de délégation :

| Résultat attendu | Nombre |
|---|---:|
| `SHORT_CODE_PATH` | 8 |
| `HIERARCHICAL_PATH` | 10 |
| `HUMAN_TRIAGE` | 2 |
| `DENY` | 7 |
| `SERIALIZE_OR_REPLAN` | 1 |
| `RECOVERED_WITHOUT_DUPLICATE_EFFECT` | 8 |

| Gate attendue | Nombre |
|---|---:|
| `NONE` | 8 |
| `BEFORE_EXTERNAL_EFFECT` | 10 |
| `BEFORE_CODE` | 2 |

Les 20 documents contractuels de référence sont chargés. Chacune des 14 sorties A2A primaires traverse le garde
de contrat réel ; sa représentation canonique JCS et son objet `usage` restent strictement identiques avant et
après validation. La consommation agrégée demeure fixée à 134 365 tokens et 543 041 ms. L'absence de prix dans
la baseline conserve la sémantique `UNAVAILABLE_NOT_ZERO` et n'est jamais convertie en coût nul.

## Reproductibilité

- Baseline post-bascule : `resources/a2a/baselines/post-cutover-e2e-v1.json`.
- Contrôle déterministe : `scripts/verify-a2a-e2e-parity.rb`.
- Campagne complète : `scripts/test-a2a-e2e-parity.sh` ou `make test-a2a-e2e-parity`.
- Les scénarios Temporal qui injectent une perte d'acquittement, un timeout ou un refus de politique écrivent des
  avertissements attendus ; le verdict est porté par les assertions et le code de sortie nul de la campagne.
