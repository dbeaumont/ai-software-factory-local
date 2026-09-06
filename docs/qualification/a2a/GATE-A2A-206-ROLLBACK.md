# Gate A2A-206 — Autorisation d'un rollback A2A

> Statut du modèle : `CLOSED`
>
> Autorité d'approbation : Exploitation (`OPERATIONS`)
>
> Effet : autoriser un rollback vers une release déjà A2A et compatible ; jamais réactiver les appels directs

## Décision demandée

Autoriser ou refuser un rollback A2A précis, après gel des admissions. La décision porte sur un manifeste JSON
conforme au contrat vérifié par `scripts/verify-a2a-rollback-gate.rb`. Aucun texte libre, ticket d'incident isolé ou
approbation portant sur une autre version ne permet d'ouvrir la gate.

## Contenu obligatoire du manifeste

- [ ] Incident identifié, horodaté et lié au digest de ses preuves.
- [ ] Commit, images digestées et Build ID Temporal de la release incidente.
- [ ] Commit, images digestées et Build ID Temporal de la cible A2A compatible.
- [ ] Inventaire digesté des huit états de tâches A2A, avec total cohérent.
- [ ] Sauvegardes Temporal, task store A2A, Evidence et projection orchestrateur toutes `VERIFIED`.
- [ ] Réconciliation couvrant exactement l'inventaire, avec `lost=0`, `duplicates=0` et `unresolved=0`.
- [ ] Compatibilité `PASS`, plancher PostgreSQL `V005`, zéro erreur de replay et zéro contrat non supporté.
- [ ] Approbation explicite `APPROVED` d'une identité Exploitation, horodatée.
- [ ] `approval.boundDigest` égal au SHA-256 du JSON canonique privé de ce seul champ.

## Exécution

```bash
make a2a-rollback-gate ROLLBACK_GATE=/chemin/vers/GATE-A2A-206.json
```

La commande échoue de manière fermée si un champ manque, si une référence d'image n'est pas liée par `@sha256:`,
si les releases ne diffèrent pas, si une sauvegarde n'est pas vérifiée, si la cible est antérieure au plancher V005,
si un état reste irrésolu ou si le digest d'approbation ne correspond pas au manifeste exact.

## Effet et limites

Une sortie `APPROVED` autorise seulement l'exécution du
[runbook de rollback A2A](../../operations/runbooks/ROLLBACK-A2A.md) pour les versions inscrites. Elle n'autorise ni
suppression de volume, ni restauration partielle, ni changement de namespace/queue, ni retrait prématuré d'un
worker, ni retour au transport direct. Toute modification du manifeste invalide automatiquement le digest
d'approbation et exige une nouvelle décision.

Le présent modèle reste `CLOSED` : il ne représente aucun incident ni aucune approbation réelle.
