# Runbook — rollback A2A avec Temporal conservé

## Détection

Appliquer ce runbook lors d'une régression A2A confirmée, d'une violation de sécurité, d'un doublon, d'une
divergence étendue ou d'un dépassement SLO qui ne peut être corrigé en place.

## Confinement immédiat

Geler toutes les nouvelles admissions A2A et les effets externes inconnus. Temporal reste l'unique orchestrateur :
le rollback ne réactive jamais les anciens appels directs/in-process et ne change pas l'autorité des historiques.

## Diagnostic

Inventorier commit, digest d'image, Agent Cards, build IDs Temporal, rôles, files, tâches, associations et Evidence
affectés. Vérifier le replay des historiques représentatifs avec la dernière version A2A qualifiée.

```bash
make a2a-status
make a2a-config
make a2a-pki
```

## Rétablissement

1. Épingler le digest de la dernière image A2A qualifiée et compatible avec les historiques.
2. Conserver task store, associations, outbox, Evidence et namespace Temporal.
3. Redéployer les runtimes avec leurs build IDs compatibles, sans supprimer de volume.
4. Réconcilier chaque effet à issue inconnue par `messageId` avant de reprendre les retries.
5. Rouvrir rôle par rôle uniquement après readiness et replay verts.

## Vérification et clôture

Exiger tous les pollers/readiness, backlog décroissant, replay déterministe, corrélations bijectives, zéro doublon,
SLO sous budget et alertes saines pendant vingt minutes. L'approbation Exploitation est obligatoire.

## Escalade

Escalader à `agent-platform`, Temporal et Sécurité. Une perte de données suit les procédures de restauration
Temporal/A2A ; ne jamais restaurer une seule base indépendamment des autres autorités.
