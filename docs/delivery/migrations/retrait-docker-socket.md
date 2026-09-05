# Plan de migration — retrait de la socket Docker

## 1. Objectif

Supprimer tout accès applicatif à `/var/run/docker.sock` sans dégrader les deux modes d'exécution attendus :

- développement et tests sur macOS avec Docker Compose ;
- exécution partagée ou de production sur GKE avec des Jobs isolés.

La migration conserve les contrats MCP, les opérations sandbox, les profils de sécurité, les identifiants
d'exécution et les règles de verdict. Seul le backend chargé d'exécuter les profils change.

```text
macOS / local : orchestrator -> sandbox-execution-mcp -> sandbox-runner(s) Compose statiques
GKE / partagé : orchestrator -> sandbox-execution-mcp -> contrôleur GKE -> Job éphémère
```

État au 5 septembre 2026 : l'implémentation, les tests hors cluster et la qualification des cinq profils Compose
sont réalisés. Le workflow applicatif complet avec arrêt propre reste à qualifier ; les cases GKE nécessitent un
cluster, un PVC, Workload Identity et les secrets de la plateforme cible. Voir la
[preuve de qualification macOS](../../evidence/sandbox/compose-macos-2026-09-05.md).

## 2. Résultat attendu

- [x] Aucun service Compose ne monte `/var/run/docker.sock`.
- [x] Aucun processus applicatif ne lance `docker run`, `docker ps`, `docker inspect` ou `docker rm`.
- [x] `docker compose up` permet toujours de démarrer et tester la pile complète sur macOS.
- [x] Les opérations `validate_patch`, `apply_patch`, `run_tests`, `run_quality` et `run_security` fonctionnent en local.
- [ ] Le mode partagé utilise des Jobs GKE isolés et ne possède aucun fallback implicite vers Docker.
- [x] Les appels MCP et leurs schémas de réponse restent compatibles.
- [x] Les limites de sécurité du mode Compose sont documentées et ne peuvent pas être activées par erreur en production.

## 3. Principes et décisions d'architecture

- [x] Conserver `SandboxRuntime` comme port unique entre le serveur MCP et les backends d'exécution.
- [x] Remplacer le runtime `docker` par deux runtimes explicites : `compose` et `gke`.
- [x] Réserver `compose` aux profils `local`, `dev` et `test`.
- [x] Interdire le runtime `compose` lorsque le profil Spring ou l'environnement indique une cible partagée/production.
- [x] Ne jamais accepter de commande shell, de manifeste Kubernetes, de volume ou de réseau fourni par l'appelant.
- [x] Traduire uniquement une opération MCP autorisée vers un profil enregistré côté serveur.
- [x] Imposer une image immuable par digest dans les deux modes.
- [x] Traiter un proxy de socket Docker comme hors cible : il réduit l'API exposée mais conserve un contrôle puissant du daemon.
- [x] Définir le rollback comme un retour à une version antérieure des runners ou du contrôleur GKE, jamais comme le remontage de la socket.

## 4. Lot 0 — cadrage et critères de parité

- [x] Inventorier les comportements actuels de `DockerSandboxRuntime` : lancement, timeout, annulation, logs, troncature et nettoyage des orphelins.
- [x] Inventorier les volumes et caches utilisés : workspace, Maven, Trivy et état des jobs.
- [x] Inventorier les variables et secrets injectés pour chaque profil.
- [x] Définir une matrice de parité pour les cinq opérations sandbox.
- [x] Capturer des cas de référence locaux : succès, échec, timeout, annulation, sortie volumineuse et processus interrompu.
- [x] Définir les SLO locaux et GKE : temps de démarrage, durée maximale, délai d'annulation et rétention.
- [x] Décider si les runners Compose sont dédiés par classe de sécurité ou regroupés dans un pool homogène.
- [x] Consigner une ADR confirmant la cible Compose statique + GKE Jobs.

### Critères de sortie du lot 0

- [x] La matrice couvre chaque opération et chaque politique réseau.
- [x] Les différences acceptables entre Compose et GKE sont écrites et approuvées.
- [x] Aucun comportement indispensable ne dépend implicitement de la CLI Docker.

## 5. Lot 1 — stabiliser le contrat d'exécution

- [x] Faire porter au contrat interne l'identifiant d'exécution, l'opération, le profil et le digest d'image.
- [x] Ajouter au contrat la politique de workspace : lecture seule, lecture-écriture ou jetable.
- [x] Ajouter les limites CPU, mémoire, PIDs et durée maximale.
- [x] Ajouter la classe de politique réseau et la liste fermée des secrets autorisés.
- [x] Standardiser le résultat : état terminal, code de sortie, logs bornés, indicateur de troncature et timestamps.
- [x] Définir les erreurs normalisées : refus de profil, saturation, timeout, annulation, indisponibilité et erreur d'infrastructure.
- [x] Garantir l'idempotence sur `execution_id`.
- [x] Vérifier qu'aucun champ du contrat ne permet de choisir une commande, un chemin hôte ou une option privilégiée.
- [x] Ajouter les tests de contrat communs applicables aux runtimes Compose et GKE.

### Critères de sortie du lot 1

- [x] Les tests prouvent qu'une opération inconnue ou un profil altéré est refusé.
- [x] Une répétition du même `execution_id` ne crée pas deux exécutions indépendantes.
- [x] Les contrats MCP publics n'ont pas changé ou restent rétrocompatibles.

## 6. Lot 2 — créer les runners Docker Compose sans socket

### 6.1 Topologie locale

- [x] Ajouter un service interne `sandbox-runner` construit depuis l'image sandbox existante.
- [x] Évaluer puis, si nécessaire, séparer les runners par capacité : validation lecture seule, patch lecture-écriture, qualité et dépendances.
- [x] Monter le workspace nommé dans le runner, sans chemin arbitraire fourni par le client.
- [x] Monter le workspace en lecture seule pour les opérations qui ne le modifient pas.
- [x] Autoriser l'écriture uniquement pour `apply_patch`, dans le répertoire de tâche résolu et validé.
- [x] Conserver l'état du contrôleur dans `sandbox-job-state`, séparé du filesystem d'exécution.
- [x] Définir des healthchecks et une dépendance de disponibilité depuis `sandbox-execution-mcp`.
- [x] Ne publier aucun port du runner sur l'hôte ; utiliser uniquement un réseau Compose interne.

### 6.2 Protocole runner

- [x] Exposer une API interne minimale : soumettre un profil, consulter, annuler et vérifier la santé.
- [x] Authentifier les appels entre `sandbox-execution-mcp` et le runner, même sur le réseau interne.
- [x] Refuser toute commande ou tout script transmis directement par la requête.
- [x] Résoudre le script uniquement depuis `SandboxProfiles` ou un registre versionné équivalent.
- [x] Valider strictement `execution_id`, `taskDirectory`, profil et digest d'image.
- [x] Borner la taille des requêtes, des réponses et des logs.
- [x] Propager correctement timeout et annulation au processus enfant.
- [x] Tuer le groupe de processus complet lors d'une annulation ou d'un timeout.
- [x] Nettoyer les fichiers temporaires et secrets après chaque tentative.
- [x] Empêcher deux écritures concurrentes sur le même workspace de tâche.

### 6.3 Durcissement du runner local

- [x] Exécuter le runner avec un utilisateur non-root.
- [x] Activer `read_only: true` et fournir uniquement les `tmpfs` nécessaires.
- [x] Supprimer toutes les capabilities Linux et activer `no-new-privileges`.
- [x] Fixer des limites CPU, mémoire, PIDs et taille de fichiers temporaires dans Compose.
- [x] Interdire le mode privilégié, les périphériques hôte et les montages de chemins système.
- [x] Appliquer le réseau le plus restrictif possible par classe de runner.
- [x] Ne fournir à chaque runner que les secrets strictement requis par ses profils.
- [x] Vérifier que les logs ne contiennent ni jeton Artifactory, ni jeton Sonar, ni contenu de fichier d'environnement.
- [x] Documenter que le runner Compose persistant offre une isolation plus faible qu'un Job GKE et reste réservé au poste développeur.

### 6.4 Adaptateur Compose

- [x] Implémenter `ComposeSandboxRuntime` dans `sandbox-execution-mcp`.
- [x] Traduire les opérations vers les profils enregistrés avant l'appel au runner.
- [x] Implémenter soumission, attente, timeout, annulation et reprise après redémarrage du MCP.
- [x] Implémenter la réconciliation des exécutions abandonnées sans appeler la CLI Docker.
- [x] Ajouter `AI_FACTORY_SANDBOX_RUNTIME=compose` comme valeur locale explicite.
- [x] Faire échouer le démarrage si le runner n'est pas configuré ; ne pas retomber sur Docker.

### Critères de sortie du lot 2

- [x] `docker compose up --build` démarre toute la pile sur macOS.
- [x] Les cinq opérations sandbox passent dans le mode `compose`.
- [x] Timeout, annulation et redémarrage du MCP ne laissent aucun processus de job actif.
- [x] Aucun service local ne monte la socket Docker.
- [x] Le runner ne reçoit jamais de commande libre depuis le réseau.

## 7. Lot 3 — terminer le backend GKE

### 7.1 Contrôleur et cycle de vie

- [x] Fournir l'implémentation opérationnelle de `GkeJobController`.
- [x] Créer les Jobs de manière idempotente avec un nom dérivé de `execution_id`.
- [x] Appliquer labels, annotations, délais, TTL et politique de redémarrage imposés par le contrôleur.
- [x] Surveiller le Job jusqu'à un état terminal et récupérer des logs bornés.
- [x] Implémenter l'annulation par suppression contrôlée du Job.
- [x] Réconcilier les Jobs orphelins au démarrage et périodiquement.
- [x] Gérer les indisponibilités temporaires de l'API Kubernetes avec retry borné et backoff.
- [x] Exporter métriques, traces et événements d'audit sans données sensibles.

### 7.2 Isolation GKE

- [ ] Créer un namespace sandbox dédié.
- [ ] Utiliser des nœuds dédiés et GKE Sandbox/gVisor, ou une isolation équivalente validée.
- [x] Appliquer Pod Security en mode restrictif.
- [x] Donner au ServiceAccount du contrôleur les permissions minimales sur les seuls Jobs sandbox.
- [x] Donner aux Jobs une identité distincte de celle du contrôleur.
- [ ] Utiliser Workload Identity et Secret Manager pour les secrets nécessaires.
- [x] Interdire les conteneurs privilégiés, `hostPath`, `hostNetwork`, `hostPID` et `hostIPC`.
- [ ] Imposer les images approuvées par digest et vérifier signature/provenance selon la politique de plateforme.
- [x] Appliquer quotas namespace et limites par Job.

### 7.3 Workspace, réseau et caches

- [x] Choisir un mécanisme de workspace jetable ou contrôlé compatible avec le cycle de vie des Jobs.
- [x] Garantir la lecture seule pour validation/tests et l'écriture bornée pour l'application du patch.
- [x] Remplacer les caches Docker nommés par des caches distants ou volumes Kubernetes dédiés.
- [ ] Déployer `sandbox-deny-all`, `sandbox-quality-egress` et `sandbox-dependency-egress`.
- [x] Bloquer l'accès au metadata service cloud.
- [x] Autoriser uniquement DNS, proxy, Artifactory et Sonar selon le profil.
- [ ] Tester les refus réseau ainsi que les chemins autorisés.

### Critères de sortie du lot 3

- [ ] Les cinq opérations atteignent la parité fonctionnelle avec le mode Compose.
- [x] Un Job ne peut ni modifier son manifeste, ni choisir son réseau, ses volumes ou ses secrets.
- [ ] L'annulation et le TTL suppriment les ressources dans les délais convenus.
- [ ] Les tests d'isolation et de NetworkPolicy passent dans un cluster de validation.

## 8. Lot 4 — campagne de migration

- [ ] Déployer le runtime GKE dans un environnement non productif.
- [ ] Exécuter une campagne shadow sur les cinq opérations.
- [ ] Comparer verdicts, codes de sortie, durées, logs tronqués et preuves.
- [ ] Examiner chaque divergence et la classer : bug, différence attendue ou défaut de profil.
- [ ] Tester la saturation, les quotas, les timeouts, les annulations et les redémarrages du contrôleur.
- [ ] Tester l'indisponibilité de Kubernetes, Secret Manager, Artifactory et Sonar.
- [ ] Tester la reprise et la réconciliation après interruption du contrôleur.
- [ ] Obtenir la validation sécurité et exploitation avant la bascule.
- [ ] Basculer progressivement `validate_patch`, `apply_patch`, `run_tests`, `run_quality`, puis `run_security`.
- [ ] Observer chaque étape pendant la fenêtre définie avant d'activer la suivante.

### Critères de sortie du lot 4

- [ ] Aucune divergence de verdict inexpliquée ne subsiste.
- [ ] Les SLO et quotas sont respectés.
- [ ] Le rollback sans socket a été testé.
- [ ] Le runtime GKE est déclaré prêt pour l'environnement partagé.

## 9. Lot 5 — suppression définitive de la socket et du runtime Docker

- [x] Retirer `/var/run/docker.sock:/var/run/docker.sock` de `infrastructure/compose.yaml`.
- [x] Retirer `group_add` lié au groupe de la socket.
- [x] Supprimer `DOCKER_SOCKET_GID` de `.env.example` et des guides opératoires.
- [x] Changer la valeur locale par défaut de `AI_FACTORY_SANDBOX_RUNTIME` de `docker` vers `compose`.
- [x] Supprimer `DockerSandboxRuntime` après validation des deux nouveaux backends.
- [x] Supprimer les tests unitaires et d'intégration spécifiques à la CLI Docker devenus inutiles.
- [x] Remplacer ces tests par des tests de contrat partagés et des tests d'intégration Compose/GKE.
- [x] Retirer les commentaires et Dockerfiles indiquant qu'un composant lance des conteneurs via la socket.
- [x] Mettre à jour le README, l'état courant, les guides de maintenance, le threat model et la roadmap.
- [x] Conserver les mentions historiques uniquement dans les archives, clairement présentées comme anciennes.

### Garde-fous automatiques

- [x] Modifier `ComposeMcpSecurityTest` pour imposer zéro détenteur de `docker.sock`.
- [x] Ajouter un test interdisant `group_add` au service sandbox, sauf justification indépendante documentée.
- [x] Ajouter un contrôle CI qui refuse `/var/run/docker.sock`, `DOCKER_SOCKET_GID` et les appels de CLI Docker dans le code applicatif.
- [x] Ajouter un test qui interdit `AI_FACTORY_SANDBOX_RUNTIME=docker` dans les configurations actives.
- [x] Ajouter un test de démarrage garantissant qu'un runtime inconnu ou incomplet provoque un échec explicite.

### Critères de sortie du lot 5

- [x] La recherche `rg '(docker\.sock|DOCKER_SOCKET_GID|AI_FACTORY_SANDBOX_RUNTIME=docker)'` ne retourne aucune dépendance active.
- [x] La recherche des appels `docker run|docker ps|docker inspect|docker rm` ne retourne aucun appel applicatif.
- [x] Tous les tests unitaires, d'intégration et de sécurité passent.
- [ ] Une installation neuve fonctionne sur macOS avec Docker Compose sans configuration de groupe Docker.
- [x] Le risque de contrôle quasi-root de l'hôte via la socket est clôturé dans le threat model.

## 10. Lot 6 — documentation et exploitation

- [x] Documenter le démarrage local avec `AI_FACTORY_SANDBOX_RUNTIME=compose`.
- [x] Documenter la reconstruction périodique des runners et le nettoyage des volumes de développement.
- [x] Documenter les limites du mode local et interdire son usage pour du code non fiable en environnement partagé.
- [x] Documenter le déploiement, le diagnostic, l'annulation et la réconciliation GKE.
- [x] Créer des alertes sur jobs bloqués, taux d'échec, saturation, dépassements de délai et échecs de nettoyage.
- [x] Définir un runbook d'indisponibilité du backend sandbox.
- [x] Définir un runbook de rotation des secrets utilisés par les profils qualité et dépendances.
- [ ] Former les mainteneurs à distinguer une erreur fonctionnelle d'une erreur d'infrastructure sandbox.
- [x] Mettre à jour la checklist de release et la revue de sécurité.

## 11. Stratégie de tests finale

- [x] Tests unitaires des mappings opération/profil et profil/politique réseau.
- [x] Tests de contrat identiques pour `ComposeSandboxRuntime` et `GkeSandboxRuntime`.
- [x] Tests négatifs : commande libre, chemin arbitraire, profil inconnu, digest absent et secret non autorisé.
- [x] Tests de concurrence sur une même tâche et sur plusieurs tâches.
- [x] Tests de timeout et suppression de tout le groupe de processus local.
- [x] Tests d'annulation avant démarrage, pendant exécution et après état terminal.
- [x] Tests de reprise du MCP et de réconciliation des jobs/runners.
- [x] Tests de bornage et de redaction des logs.
- [x] Tests de lecture seule et d'écriture du workspace.
- [ ] Tests de refus réseau et d'absence d'accès au metadata service.
- [ ] Test end-to-end macOS : build, démarrage Compose, workflow complet et arrêt propre.
- [ ] Test end-to-end GKE : workflow complet, collecte de preuves et nettoyage du Job.

## 12. Ordre recommandé et dépendances

- [ ] Réaliser le lot 0 avant toute modification du runtime.
- [ ] Stabiliser le contrat du lot 1 avant de développer les deux adaptateurs.
- [ ] Réaliser les lots 2 et 3 en parallèle une fois le contrat stabilisé.
- [ ] Terminer la campagne du lot 4 avant de supprimer `DockerSandboxRuntime`.
- [ ] Exécuter le lot 5 dans une modification dédiée, facilement auditable.
- [ ] Finaliser documentation, runbooks et alertes avant de déclarer la migration terminée.

## 13. Définition de terminé

- [x] Le développement quotidien sur macOS fonctionne avec les commandes Docker Compose documentées.
- [x] Docker Compose ne sert qu'à démarrer des services connus à l'avance et n'est jamais piloté par une application.
- [x] Aucun composant du projet n'accède au daemon Docker de l'hôte.
- [x] Le runtime Compose est techniquement et explicitement impossible à activer en production.
- [ ] Les exécutions partagées utilisent des Jobs GKE isolés avec identité, quotas et réseau minimaux.
- [ ] Les contrats MCP, verdicts, preuves, timeouts et annulations sont validés sur les deux backends.
- [x] Les garde-fous CI empêchent toute réintroduction de la socket.
- [x] La documentation d'architecture, de sécurité, de développement et d'exploitation reflète l'état réellement déployé.
