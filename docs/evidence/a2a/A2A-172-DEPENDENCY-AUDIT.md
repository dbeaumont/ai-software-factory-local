# A2A-172 — Audit des dépendances et images

Date : 2026-09-06

## Verdict

- **Statut : réussi**
- **Tests :** 273 rapports Surefire, 801 tests, 0 échec, 0 erreur et 1 test ignoré.
- **Analyse statique :** compilation JDK 25, règles Maven Enforcer et contrôles d'architecture du réacteur réussis.
- **Sources :** 10 manifestes analysés, 0 vulnérabilité HIGH/CRITICAL et 0 secret détecté.
- **Images :** 7 images Java analysées, 0 vulnérabilité HIGH/CRITICAL et 0 secret détecté.
- **Licences :** 8 rapports Maven, 1 287 occurrences de dépendances, aucune licence inconnue.

## Correctifs issus de l'audit

Le premier scan du runtime A2A a détecté huit vulnérabilités HIGH dans le binaire `pebble` de l'ancienne image
Temurin Ubuntu. Le passage à l'image Temurin 25 Alpine épinglée a supprimé ce composant. Un premier build Alpine
a ensuite exposé cinq vulnérabilités HIGH corrigées dans les dépôts Alpine ; les sept Dockerfiles Java exécutent
désormais `apk upgrade --no-cache` avant d'installer leurs outils d'exploitation. Le scan final est vert.

Les gardes de configuration ont aussi été remis en cohérence avec les neuf alertes A2A et le dashboard A2A déjà
livrés. Le test de l'image worker contrôle maintenant la classe de replay obligatoire sans dépendre de la forme
exacte de la commande Maven du Dockerfile.

## Images qualifiées

| Image locale | Digest Docker | HIGH/CRITICAL |
|---|---|---:|
| `ai-factory-a2a-agent-runtime:a2a-172` | `sha256:2b65b20d09414e46b92868e2cb82a88e67330b6a57544b67b5608d047f42d4ca` | 0 |
| `ai-factory-orchestrator:a2a-172` | `sha256:ff8e04dae63cfc6937c802db470f8df39da05aade1e97099ff73de244183f556` | 0 |
| `ai-factory-repository-context-mcp:a2a-172` | `sha256:91b2e7f6bd8d8da2353ef8b8f63f2fc2a92af88f4639e76b577cb07f96f4e22b` | 0 |
| `ai-factory-sandbox-execution-mcp:a2a-172` | `sha256:345bffa6d0c971f6793327caa7e6a20b33814f3929d3cf8c967bc6d7bef1adce` | 0 |
| `ai-factory-scm-delivery-mcp:a2a-172` | `sha256:d46634801cdc55793f63fb435ba7a1128518b890b321b59becded4299e67a701` | 0 |
| `ai-factory-assurance-mcp:a2a-172` | `sha256:3485d3be8de6b767d4e9e2ffffeeade7d55ca46bfee1791149221e8a31ea6859` | 0 |
| `ai-factory-evidence-mcp:a2a-172` | `sha256:0bad2891a0261d2c6eb7d06b8166295787dc75c91a3f05dca1b4b3ccd1814ee2` | 0 |

Les images sont exportées avec `docker save`, puis les archives sont montées en lecture dans l'image de sécurité.
Trivy n'accède donc jamais à la socket Docker.

## SBOM

Syft 1.30.0 a produit un SBOM CycloneDX 1.6 et un SBOM SPDX 2.3 du workspace, ainsi qu'un SBOM CycloneDX pour
chaque image. Les empreintes des deux inventaires globaux sont :

- CycloneDX, 1 104 composants : `bd4bd4d6ce2def99f8c359d7faf971702e9ee079531aec98d5e482b2c9c4a508` ;
- SPDX, 1 088 paquets : `089cc13e553ff86fdd12bab5b7d3d4f0683dd11cbcc097bfe63ea2708bab1e1a`.

## Licences

`license-maven-plugin:2.6.0:add-third-party` a été exécuté avec les dépendances transitives et
`license.failOnMissing=true` sur le réacteur principal et les cinq modules MCP. Les dépendances internes ont été
exclues du rapport tiers. Les licences rencontrées sont Apache-2.0, BSD, MIT/MIT-0, EPL/EDL, MPL-2.0, CC0 et les
choix doubles EPL-2.0/LGPL-2.1 ou EPL-2.0/GPL-2.0-with-Classpath-Exception. Pour ces derniers, l'option EPL-2.0
admise par la politique est retenue. Aucun composant n'impose AGPL, GPL sans alternative ou SSPL.

## Commandes de preuve

```shell
mvn clean verify
mvn -f apps/mcp/repository-context-server/pom.xml clean verify
mvn -f apps/mcp/sandbox-execution-server/pom.xml clean verify
mvn -f apps/mcp/scm-delivery-server/pom.xml clean verify
mvn -f apps/mcp/assurance-server/pom.xml clean verify
mvn -f apps/mcp/evidence-server/pom.xml clean verify

mvn org.codehaus.mojo:license-maven-plugin:2.6.0:add-third-party \
  -Dlicense.force=true -Dlicense.failOnMissing=true \
  -Dlicense.useMissingFile=false -Dlicense.excludedGroups=com.example.aifactory

trivy fs --scanners vuln,secret --severity HIGH,CRITICAL \
  --ignore-unfixed=false --exit-code 1 /workspace
trivy image --scanners vuln,secret --severity HIGH,CRITICAL \
  --ignore-unfixed=false --exit-code 1 --input image.tar
```

Les rapports bruts de cette exécution sont conservés hors Git dans `/tmp/ai-factory-a2a-172`; leurs résultats
synthétiques, commandes, versions et digests sont figés dans cette preuve.
