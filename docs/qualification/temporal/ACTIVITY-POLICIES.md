# Politiques temporelles des activités V1

| Famille | Schedule-to-start | Start-to-close | Schedule-to-close | Heartbeat | Tentatives | Backoff initial / max |
|---|---:|---:|---:|---:|---:|---:|
| `READ` | 30 s | 30 s | 2 min | aucun, appel court | 3 | 200 ms / 2 s |
| `LLM` | 2 min | 10 min | 20 min | aucun avant streaming supervisé | 2 | 2 s / 20 s |
| `SANDBOX` | 5 min | 30 min | 45 min | 30 s | 2 | 2 s / 30 s |
| `ASSURANCE` | 1 min | 90 s | 5 min | aucun, appel court | 3 | 500 ms / 5 s |
| `EVIDENCE` | 1 min | 2 min | 5 min | aucun, appel court | 3 | 500 ms / 5 s |
| `SCM` | 2 min | 4 min | 10 min | aucun, effet atomique | 2 | 1 s / 10 s |

Le coefficient de backoff est `2.0`. `BUSINESS_REJECTION`, `CONTRACT_ERROR` et `EFFECT_OUTCOME_UNKNOWN` ne sont
jamais retentés automatiquement. L'absence de heartbeat est explicite pour les appels courts ou atomiques ; les
jobs sandbox longs doivent émettre un heartbeat et porter leur progression pour permettre une reprise sûre.
