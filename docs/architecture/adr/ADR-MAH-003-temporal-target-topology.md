# ADR-MAH-003 — Topologie Temporal cible

- Statut : accepté pour la cible d'industrialisation
- Date : 2026-09-02
- Portée : environnements intégration, préproduction et production

## Contexte

La cible GCP doit exécuter des workflows longs avec délégations parallèles, signaux humains, reprise et
versionnement. GCP ne fournit pas de service Temporal natif. Deux topologies réalistes restent possibles :
Temporal Cloud ou un cluster Temporal exploité sur GKE.

## Décision

1. **Temporal Cloud est la topologie cible par défaut**, sous réserve de validation contractuelle, résidence des
   données, connectivité privée, chiffrement, SSO/RBAC, export d'audit et réversibilité.
2. **Temporal self-managed sur GKE est le profil souverain**, utilisé lorsqu'un service SaaS externe est interdit
   ou lorsque les exigences réseau et de conservation l'imposent.
3. L'application ne dépend d'aucune API spécifique à Temporal Cloud : elle utilise le SDK et les API Temporal
   portables.
4. Namespace, task queues, rétention, clés de chiffrement et identités sont séparés par environnement.
5. Les workers Spring Boot utilisent une identité de charge dédiée et des certificats ou jetons courts.
6. Aucun prompt, code source, log de sandbox ou preuve brute ne doit être placé dans l'historique Temporal.
7. L'historique ne contient que les identifiants, états, décisions et références d'artefacts nécessaires à la
   reprise déterministe.

## Profil par environnement

| Environnement | Topologie | Objectif |
|---|---|---|
| Local | Temporal dans Compose | Développement reproductible sans dépendance externe |
| CI | Serveur éphémère ou namespace CI isolé | Tests de workflows et de reprise |
| Intégration | Temporal Cloud non-production par défaut | Validation réseau, IAM, observabilité et charge |
| Production standard | Temporal Cloud | Réduire l'exploitation du moteur durable |
| Production souveraine | Temporal sur GKE dédié | Maîtriser hébergement, réseau et conservation |

## Exigences avant production

- connectivité privée ou filtrée entre workers et frontend Temporal ;
- chiffrement en transit et codec de payload pour les métadonnées sensibles résiduelles ;
- séparation des namespaces et droits d'administration ;
- export des métriques et événements d'audit vers l'observabilité d'entreprise ;
- procédure de sauvegarde, restauration et reprise régionale pour le profil self-managed ;
- test de bascule d'un worker sans perte ni duplication d'effet ;
- plan de réversibilité entre Temporal Cloud et self-managed fondé sur des workflows compatibles.

## Conséquences

- le choix final du profil production dépend d'une validation sécurité, juridique et exploitation ;
- Temporal Cloud réduit la charge opératoire mais ajoute une dépendance SaaS ;
- le profil GKE nécessite une équipe propriétaire du stockage, des upgrades et du PRA ;
- le code et les contrats restent identiques entre les deux profils.

## Alternatives écartées

- **Cloud Workflows comme cible complète** : adapté aux orchestrations GCP simples, mais moins naturel pour la
  hiérarchie dynamique, les replans et l'état fin des agents.
- **Un moteur différent par environnement** : les différences de sémantique rendraient les tests locaux moins
  représentatifs.
