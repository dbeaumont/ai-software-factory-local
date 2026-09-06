# A2A-163 — Interopérabilité bidirectionnelle

## Résultat

La qualification bidirectionnelle A2A 1.0 est réussie :

- le client JSON-RPC Java du projet appelle un serveur de référence construit avec le SDK Python officiel ;
- le client du SDK Python officiel appelle le serveur Java du projet ;
- les deux tâches atteignent l'état terminal `COMPLETED` ;
- le client du projet respecte le profil asynchrone `returnImmediately` et poursuit la tâche avec `GetTask`.

La sortie brute de la dernière campagne est archivée dans
`docs/evidence/a2a/A2A-163-INTEROPERABILITY.log`.

## Références épinglées

- protocole : A2A `1.0` ;
- SDK officiel : `a2aproject/a2a-python` tag `v1.0.0` ;
- révision vérifiée : `24db37ee24c927df936289ad6ffbc8c746a44db8`.

Le script échoue avant le build si la révision résolue ne correspond pas à cette valeur.

## Reproduction

Pré-requis : images locales `ai-software-factory-orchestrator:latest` et
`ai-factory-a2a-agent-runtime:0.1.0`, ainsi que les secrets locaux A2A générés.

```bash
scripts/test-a2a-interop.sh
```

Le script construit d'abord l'image publique de référence, puis exécute les deux serveurs et les deux clients sur
un réseau Docker éphémère `--internal`. Les conteneurs sont en lecture seule, sans capacités Linux et avec
`no-new-privileges`. Le réseau et les conteneurs sont supprimés à la fin de la campagne.

## Barrières complémentaires

- `A2aJsonRpcHttpTransportTest` vérifie la méthode `SendMessage`, l'en-tête `A2A-Version: 1.0`, la configuration
  asynchrone et le mapping d'une tâche officielle vers les contrats internes.
- le build orchestrateur exécute la barrière ciblée A2A/Temporal : **83 tests réussis, aucun échec**.
- la qualification ne modifie pas le runtime serveur pour simuler le client Java ; elle utilise le transport
  JSON-RPC concret livré par l'orchestrateur.
