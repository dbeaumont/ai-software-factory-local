# A2A-167 — Qualification de concurrence et de rotation

Date : 2026-09-06

## Résultat

- **Statut : réussi**
- Douze admissions concurrentes portant le même `messageId` ne créent qu'une tâche et qu'un événement
  `MESSAGE_ACCEPTED` en PostgreSQL.
- Une annulation et une terminaison concurrentes sur la même version de tâche sont sérialisées : une seule
  transition terminale gagne et l'historique reste cohérent.
- La rotation de la PKI A2A et la rotation des secrets A2A passent leurs campagnes dédiées.
- Une exécution Temporal en attente humaine survit au redémarrage de l'orchestrateur et au remplacement de
  tous ses workers, sans changement de `runId`.

## Commandes et preuves

| Vérification | Commande | Résultat |
|---|---|---|
| Concurrence PostgreSQL et course terminale | build/tests Compose de `a2a-agent-runtime` | 67 tests, 0 échec, 0 erreur |
| Rotation de certificats | `./scripts/test-a2a-pki-rotation.sh` | réussi |
| Rotation de secrets | `./scripts/test-a2a-secret-rotation.sh` | réussi |
| Redémarrage et rotation Temporal | `./scripts/test-temporal-human-wait-rotation.sh` | réussi |

Dernière campagne Temporal :

```text
task=e5ccc407
run=01a07832-60ca-7d2d-bcb5-265616d157a2
wait_seconds=49
old_build=0.1.0
new_build=0.1.0-wait-1788722960
```

Le script attend désormais que la version de worker annonce les sept task queues avant de l'activer. Il
charge aussi `.env` comme un fichier Docker Compose, sans exécuter ses valeurs comme du code shell.
