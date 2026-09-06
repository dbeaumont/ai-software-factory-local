# A2A-142 — métriques du client A2A

## Instruments OTLP

| Mesure | Type | Dimensions bornées |
|---|---|---|
| `ai.factory.a2a.client.duration` | timer | rôle, skill, opération, version, état, résultat |
| `ai.factory.a2a.client.payload.bytes` | histogramme | rôle, skill, opération, version, état |
| `ai.factory.a2a.client.retries` | compteur | rôle, skill, opération, version, état, résultat |
| `ai.factory.a2a.client.timeouts` | compteur | rôle, skill, opération, version, état, résultat |
| `ai.factory.a2a.client.reconciliations` | compteur | rôle, opération, version, état, résultat |
| `ai.factory.a2a.client.card.validations` | compteur | rôle, opération, version, état, résultat |
| `ai.factory.a2a.client.notification.age` | histogramme | rôle, opération, version, état |

Les activités mesurent résolution de carte, envoi, lecture, annulation et continuation. La réconciliation distingue
association durable, tâche distante retrouvée et nouveau dispatch. Le récepteur de notification mesure l'âge avant
le signal Temporal. Les timeouts sont classifiés en parcourant la chaîne de causes.

## Validation

`A2aClientMetricsTest`, `A2aActivitiesTest` et `TemporalA2aNotificationReceiverTest` sont verts. Le test inspecte
tous les tags exportés et interdit les IDs et tenants.
