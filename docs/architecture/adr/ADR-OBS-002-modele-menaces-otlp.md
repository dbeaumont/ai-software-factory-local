# ADR-OBS-002 — Modèle de menaces des frontières OTLP

- Statut : accepté
- Date : 2026-09-05
- Portée : Collector local et gateway GKE

## Actifs et frontières

Les actifs protégés sont les secrets, contenus GenAI, code, patchs, preuves, identifiants métier, capacité du
Collector et credentials Google Cloud. La frontière locale est limitée aux réseaux Compose internes. La frontière
GKE accepte uniquement le mTLS depuis les namespaces explicitement labellisés et exporte vers Google Cloud par
Workload Identity.

## Menaces et traitements

| Menace | Traitement versionné | Preuve |
|---|---|---|
| Exfiltration par attribut, URL, header ou exception | liste fermée côté application, suppression Collector des clés de contenu et d'authentification | test de contrat et canary `test-otel-redaction.sh` |
| Endpoint OTLP exposé à l'hôte ou à Internet | aucun port OTLP publié en Compose ; Service GKE `ClusterIP`, mTLS et NetworkPolicy | test d'architecture et rendu Kustomize |
| Falsification d'un émetteur | isolation réseau locale ; CA cliente exigée sur GKE | configuration receiver OTLP |
| Déni de service par volume ou message | messages 4 MiB maximum, memory limiter, batchs, files et ressources bornés | validation Collector et test de panne |
| Backpressure vers le métier | timeouts courts, exports asynchrones, retry borné et fail-open applicatif | arrêt Collector/backend avec applications saines |
| Fuite dans les logs de rejet | niveau `info`, aucun exporter `debug`, corps invalides non journalisés | injection invalide HTTP 400 |
| Vol de credentials cloud | aucune clé montée ; Workload Identity et rôles writer minimaux | manifeste GKE et prérequis IAM |
| Réintroduction de la socket Docker | aucune collecte de conteneur privilégiée ; contrôle CI sur Compose | test d'architecture |
| Cardinalité ou coût non borné | identifiants réservés aux traces/logs, valeurs métriques fermées, rétentions bornées | tests de contrat et runbook coût |

## Risques résiduels

- Un service autorisé peut encore produire une clé nouvelle avant mise à jour des règles de redaction. Toute
  extension du contrat doit donc ajouter son test négatif dans le même changement.
- Le certificat GKE, les liaisons IAM et les politiques VPC ne peuvent être éprouvés sans projet de validation.
- Les files mémoire locales privilégient volontairement la continuité métier à la livraison garantie de la
  télémétrie lors d'une panne longue.

Les propriétaires sécurité et plateforme doivent réexaminer ce modèle lors d'une ouverture réseau, d'une capture
de contenu, d'un changement de backend ou d'une montée majeure du Collector.
