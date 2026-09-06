# A2A-221 — Suppression des flags de transport

Date : 2026-09-07

## Verdict

- **Statut : réussi**
- **Suite complète orchestrateur :** 547 tests, 0 échec, 0 erreur, 1 test ignoré.
- **Sélecteur supprimé :** `AI_FACTORY_A2A_ENABLED`.

## Configuration finale

Le client A2A, le validateur des cartes, les activités Temporal A2A et la readiness de flotte font désormais
partie inconditionnelle du graphe Spring. La configuration de release ne peut plus :

- désactiver A2A ;
- enregistrer un worker Temporal sans ses activités A2A ;
- contourner la readiness A2A lors d'une admission ;
- ignorer la réconciliation A2A avant un retry opérateur.

Une panne de carte, d'identité, d'OAuth2, de notification, de mTLS, de task queue ou de Temporal suspend les
admissions. Elle ne sélectionne aucun runtime local, mode shadow ou fallback.

`A2aCutoverConfigurationTest` inspecte les fichiers de release et le graphe de production afin d'empêcher le
retour d'un flag ou d'une condition d'enregistrement A2A.

## Reproductibilité

```bash
mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -pl apps/orchestrator test
```
