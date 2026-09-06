# A2A-169 — Performance et saturation

Date : 2026-09-06

## Verdict

- **Statut : réussi**
- **Commande :** `make test-a2a-performance`
- **Environnement :** Docker Desktop macOS, runtime `developer`, PostgreSQL et générateur de charge isolés.
- **Preuve brute :** `docs/evidence/a2a/A2A-169-PERFORMANCE.json`.

## Mesures

| Mesure | A2A après migration | Baseline pré-A2A | Lecture |
|---|---:|---:|---|
| Latence p50 | 204,863 ms | 18 363 ms | admission/rejeu A2A = 1,12 % de la latence E2E historique |
| Latence p95 | 398,784 ms | 66 272 ms | admission/rejeu A2A = 0,60 % de la latence E2E historique |
| Latence p99 | 493,049 ms | 80 544 ms | admission/rejeu A2A = 0,61 % de la latence E2E historique |
| Débit | 68,225 requêtes/s | campagne séquentielle | gain de capacité mesuré, ratio historique non calculable |
| Concurrence | 16 | 1 | charge concurrente explicitement qualifiée |
| Mémoire runtime après charge | 299 Mio | indisponible | 38,9 % de la limite conteneur de 768 Mio |
| Taille de requête | 936 octets | 237 octets p95 reconstruits | enrichissement attendu par contexte A2A complet |
| Coût fournisseur | non applicable au test protocolaire | indisponible | aucune valeur absente n'est assimilée à zéro |

Les latences ne sont pas comparées comme deux parcours fonctionnels identiques : la baseline inclut le pipeline et
les appels LLM, tandis que cette campagne isole le coût d'admission, de validation, de persistance et de rejeu A2A.
Elle démontre que cette couche consomme moins de 1 % du budget p95 historique. Les usages LLM et leurs coûts sont
comparés sur les fixtures métier dans A2A-170.

## Backpressure

La capacité active a été fixée à 32 tâches. Une tâche de rejeu étant déjà active, 64 messages uniques concurrents
ont produit exactement 31 admissions et 33 refus `QUOTA_EXCEEDED`. Aucun dépassement de capacité n'a été observé ;
le runtime est resté sain et sous sa limite mémoire. Le projet Compose, son réseau interne et son volume PostgreSQL
sont supprimés automatiquement après la campagne.
