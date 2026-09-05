# Sauvegarde de retrait Grafana — 2026-09-05

- Créée avant l'arrêt de la chaîne historique et le retrait de `grafana-data` du Compose actif.
- Emplacement local récupérable : `/private/tmp/ai-factory-observability-backup-2026-09-05/`.
- Date d'expiration proposée : **2026-10-05**, suppression uniquement après validation explicite de la stabilité.
- La restauration exige le commit précédant `OTEL-100` ; aucun rollback partiel n'est supporté.

| Archive | Taille observée | SHA-256 |
|---|---:|---|
| `ai-software-factory_grafana-data.tgz` | 22 MiB | `42ef1625ac5e03d4e71869364c846862e645bc2a507a12c70ee29b43a44fb923` |
| `ai-software-factory-local_grafana-data.tgz` | 22 MiB | `4652d1317b9cc18ee8d6dc111e511e0cbc237619bcd6a8df1e3fa99d36c7dd51` |

Les archives contiennent potentiellement des réglages ou données opérateur : elles restent hors Git et doivent être
protégées comme une sauvegarde. Les dashboards et règles versionnés sont également préservés par le commit Git
précédant la bascule et par les fixtures expurgées de ce répertoire.
