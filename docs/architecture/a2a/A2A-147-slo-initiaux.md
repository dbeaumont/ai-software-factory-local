# A2A-147 — SLO initiaux des échanges A2A

> Statut : supervisé, cible initiale à réévaluer après 28 jours de trafic représentatif
>
> Politique : `resources/a2a/slo-policy-v1.json`
>
> Fenêtre : 28 jours glissants — minimum 100 tâches éligibles par rôle

## Objectifs

| SLO | Cible | SLI | Périmètre |
|---|---:|---|---|
| disponibilité du dispatch | `>= 99,5 %` | dispatchs `send` réussis / dispatchs éligibles | par rôle |
| délai de prise en charge | p95 `<= 10 s` | `WORKING.occurred_at - submitted_at` | par rôle |
| délai de terminaison | p95 `<= 30 min` | `terminal.occurred_at - submitted_at` | par rôle |
| exécution en doublon | `0` | exécutions métier répétées malgré l'idempotence | flotte |
| propagation d'annulation | p95 `<= 30 s` | appel d'annulation à confirmation `CANCELED` | par rôle |

Le budget de disponibilité autorise 5 échecs système pour 1 000 dispatchs éligibles. L'exécution en doublon est
un invariant : un seul événement impose le gel des admissions, sans attendre l'épuisement d'un budget.

## Éligibilité et sémantique

Les dispatchs authentifiés et admis sont éligibles. Les refus d'autorisation ou de politique, les cartes et
enveloppes invalides ainsi que le trafic de test opérateur sont exclus : ces réponses démontrent le respect du
contrat et non une indisponibilité. Un replay idempotent qui retourne la tâche originale est un succès ; il ne
constitue ni une nouvelle exécution ni un doublon.

La prise en charge commence lors de l'admission durable A2A et s'arrête à la première transition `WORKING`. La
terminaison accepte `COMPLETED`, `FAILED`, `REJECTED` ou `CANCELED`, tout en conservant l'état comme dimension. Le
délai d'annulation est mesuré côté orchestrateur jusqu'à confirmation A2A, transport compris.

## Mesure SigNoz

Le dashboard `AI Factory A2A Fleet` publie les cinq SLI à partir de :

- `ai.factory.a2a.client.duration` pour disponibilité et annulation ;
- `ai.factory.a2a.server.pickup.duration` pour la prise en charge ;
- `ai.factory.a2a.server.task.duration` pour la terminaison ;
- `ai.factory.a2a.server.duplicate.executions` pour l'invariant de non-duplication.

Les identifiants de tâche, message, workflow, contexte, délégation, tenant ou appelant restent interdits dans les
dimensions. En dessous de 100 tâches sur la fenêtre, le verdict est `DONNEES_INSUFFISANTES`, jamais conforme.

## Budget et décision d'exploitation

- fast burn : 6 fois le budget sur 1 h et 6 h ;
- slow burn : 2 fois le budget sur 6 h et 3 jours ;
- violation de non-duplication : gel immédiat des admissions A2A ;
- dépassement p95 sur deux évaluations consécutives : incident et réduction de capacité admise ;
- toute modification des cibles exige une nouvelle version de politique et conserve la précédente.

Le propriétaire est `agent-platform`. Après 28 jours de trafic représentatif, produit et exploitation confirment
ou ajustent les cibles sans réécrire l'historique de mesure.
