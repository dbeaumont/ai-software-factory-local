# MCP-076 — Profils de tests et egress contrôlé

## Profils exécutables

`sandbox.run_tests` conserve une sélection déterministe depuis les manifests du workspace et n'accepte aucune commande réseau fournie par l'appelant.

- `test-maven-v1` impose le `MAVEN_MIRROR_URL` approuvé et le fichier de réglages du contrôleur ;
- `test-gradle-v1` impose le wrapper du dépôt et un init script serveur qui remplace les dépôts du build et des plugins par le même miroir approuvé ;
- `test-node-v1` impose `package-lock.json`, `npm ci --ignore-scripts` et le registre npm configuré côté serveur.

L'image sandbox contient Node.js 24.20.0 LTS et npm. Maven reste fourni par l'image de base ; Gradle reste fourni par le wrapper du dépôt afin de respecter la version déclarée par le projet.

## Frontière réseau locale

Les jobs de test rejoignent exclusivement `ai-factory-sandbox-egress`, un réseau Docker `internal: true`. Seuls les composants suivants le rejoignent également :

- Artifactory, pour les dépendances Maven/Gradle ;
- `sandbox-egress-proxy`, qui est le seul pont vers le réseau sortant.

Le proxy Squid refuse par défaut et n'autorise que les domaines publics nécessaires aux distributions Maven/Gradle et au registre npm. Les services du plan de contrôle (orchestrateur, Gitea, LiteLLM, bases, web et MCP) ne sont pas présents sur le segment des tests. Le profil qualité utilise un second réseau interne borné, `ai-factory-sandbox-quality`, qui ajoute SonarQube sans l'exposer aux tests. Les profils sans réseau continuent d'utiliser `--network none`.

Un conteneur d'initialisation dépose le fichier officiel `artifactory.config.import.yml` avant le premier démarrage d'Artifactory OSS et demande la création de ses dépôts Maven par défaut. Sur un poste de développement connecté à Internet et sans jeton Artifactory lecteur, `AI_FACTORY_SANDBOX_MAVEN_MIRROR_URL` cible Maven Central exclusivement à travers le proxy allow-listé. En environnement intégré, cette variable doit cibler le virtuel Artifactory approuvé et `ARTIFACTORY_TOKEN` doit contenir un jeton lecteur fourni par la plateforme. Le réglage distinct `MAVEN_MIRROR_URL`, réservé aux builds hôte/BuildKit, peut rester vide sur un poste connecté à Internet.

## Limites et cible GCP

Cette séparation constitue l'implémentation Docker locale de MCP-076. Les politiques GKE, le blocage de la metadata cloud, la résolution DNS contrôlée et les identités réseau par workload restent suivis par MCP-216. L'allow-list du proxy doit être revue comme du code à chaque nouvelle destination.
