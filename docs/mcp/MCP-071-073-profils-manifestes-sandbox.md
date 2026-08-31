# MCP-071 à MCP-073 — Profils et manifestes sandbox

## Sélection des profils de test

Le serveur sélectionne le profil depuis les manifests présents à la racine du workspace enregistré. Le contrat `sandbox.run_tests` n'accepte ni commande, ni image, ni identifiant de profil.

L'ordre déterministe est Maven (`mvnw` ou `pom.xml`), Gradle (`gradlew`, `build.gradle` ou `build.gradle.kts`), puis Node (`package.json`). Maven utilise le réglage Artifactory du serveur. Gradle exige le wrapper pour l'exécution. Node exige `package-lock.json` et exécute `npm ci --ignore-scripts` avant les tests. Un dépôt sans manifest reconnu échoue fermé.

Les profils publiés sont :

- `patch-check-v1` et `patch-apply-v1` sans réseau ;
- `test-maven-v1`, `test-gradle-v1` et `test-node-v1` ;
- `quality-sonar-v1` et `security-syft-trivy-v1`.

Modifier une commande, une limite ou une politique réseau exige un nouvel identifiant majeur de profil.

## Manifeste persistant

Chaque admission de job fige et valide dans son snapshot : profil, référence d'image, digest lorsqu'il est présent, identifiant du workspace, mémoire, CPU, PIDs, timeout, politique réseau, lecture seule du workspace, présence du cache Maven et noms des variables autorisées. Les valeurs des secrets ne sont jamais persistées.

Le lecteur accepte encore les snapshots antérieurs dépourvus de manifeste afin de préserver la compatibilité de reprise. Toute nouvelle admission écrit le manifeste.

## Éléments restant bloquants

MCP-073 et MCP-076 restent ouvertes tant que l'image d'exécution n'est pas publiée puis configurée sous la forme `image@sha256:<digest>`, que les miroirs Gradle/npm ne sont pas imposés et que l'egress `factory` n'est pas remplacé par une allow-list effectivement contrôlée.
