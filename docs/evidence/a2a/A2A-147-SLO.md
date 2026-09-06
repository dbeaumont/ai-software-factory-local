# Preuve A2A-147 — SLO A2A supervisés

Date : 2026-09-06

## Contrat livré

La politique `resources/a2a/slo-policy-v1.json` définit cinq objectifs sur 28 jours glissants et exige au moins
100 tâches éligibles par rôle avant de produire un verdict : disponibilité du dispatch à 99,5 %, prise en charge
p95 sous 10 secondes, terminaison p95 sous 30 minutes, aucune exécution dupliquée et propagation d'annulation p95
sous 30 secondes.

La sémantique, l'éligibilité, les budgets fast/slow burn et les décisions d'exploitation sont documentés dans
`docs/architecture/a2a/A2A-147-slo-initiaux.md`. L'invariant de non-duplication ne possède aucun budget d'erreur.

## Instrumentation et supervision

Le runtime mesure désormais `SUBMITTED -> WORKING` dans
`ai.factory.a2a.server.pickup.duration`. Le compteur `ai.factory.a2a.server.duplicate.executions` est enregistré à
zéro dès le démarrage et doit rester nul. Les métriques client et de durée terminale existantes complètent les
trois autres SLI.

Le dashboard `AI Factory A2A Fleet` contient cinq panneaux SLO supplémentaires. Toutes leurs agrégations restent
limitées aux dimensions bornées du contrat A2A.

```text
A2A SLO policy validated: 5 supervised objectives, 28-day window, bounded dimensions.
A2A SigNoz dashboard validated: 15 panels, 22 bounded queries.
Validated 104 unique SigNoz dashboard and alert queries.
SigNoz telemetry ready: metrics=792 dashboards=8 alerts=30 retention=720h/360h/15d
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Le dashboard étendu a été reprovisionné dans SigNoz et toutes les requêtes ont été acceptées par l'API locale.
