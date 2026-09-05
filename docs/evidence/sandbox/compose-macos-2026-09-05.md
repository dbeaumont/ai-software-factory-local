# Qualification du runtime sandbox Compose sur macOS — 5 septembre 2026

## Périmètre

- Hôte : macOS Apple Silicon (`arm64`).
- Docker Engine : `29.7.2` ; Docker Compose : `5.3.1`.
- Image sandbox qualifiée :
  `sha256:fb3d0de953dc1e258cd39178427cf41a745e596b61fe1881cab183411d42fc88`.
- Workspace jetable : `socket-migration-e2e`, créé dans le volume nommé puis supprimé après la campagne.

## Résultats

| Contrôle | Résultat | Preuve synthétique |
|---|---|---|
| Construction | conforme | `make build` termine avec `Build complete!` et épingle l'image par digest. |
| Démarrage | conforme | `make up` démarre la pile ; le MCP sandbox et les quatre runners sont `healthy`. |
| `validate_patch` | conforme | `patch-check-v1`, code `0`. |
| `apply_patch` | conforme | `patch-apply-v1`, code `0`, modification du workspace vérifiée. |
| `run_tests` | conforme | `test-maven-v1`, code `0`, trois tests exécutés ; sortie bornée signalée comme tronquée. |
| `run_quality` | conforme | `quality-sonar-v1`, code `0`, analyse acceptée et quality gate `PASSED`. |
| `run_security` | conforme, verdict bloquant | Syft produit un SBOM CycloneDX ; Trivy termine le scan avec le code métier `1` après avoir détecté trois vulnérabilités critiques dans Tomcat `11.0.24`. |
| Cache sécurité | conforme | la base Trivy est téléchargée dans le volume persistant inscriptible ; aucun épuisement du tmpfs de 64 Mio. |
| Artefacts | conforme | SBOM de 9 802 octets et rapport Trivy de 5 255 octets présents dans `.ai-factory/`. |
| Redémarrage et réconciliation | conforme | une exécution Maven longue, active avant redémarrage du MCP, est annulée au redémarrage ; le runner retourne ensuite `execution_ids: []`. |

Un code `1` de Trivy est ici le résultat attendu du contrôle de sécurité : l'opération et
l'infrastructure ont réussi, puis la politique a refusé un composant vulnérable. Les versions corrigées
indiquées par la base commencent à Tomcat `11.0.25`.

## Contrôles automatisés complémentaires

- `make test` : suite complète réussie pour l'orchestrateur et les cinq serveurs MCP après réalignement des
  contrats d'alertes et de runbooks.
- `python3 -W error::ResourceWarning -m unittest infrastructure/sandbox/test_runner.py` : 7 tests réussis.
- `ComposeMcpSecurityTest` : réussi avec un dépôt Maven temporaire alimenté depuis Central, le miroir Maven
  configuré sur l'hôte étant momentanément indisponible.
- `docker compose ... config --quiet` : configuration valide.
- `git diff --check` : aucun défaut de whitespace.
- Les tests de contrat partagés Compose/GKE couvrent le mapping des opérations, les résultats normalisés,
  les timeouts et le refus des profils non autorisés.
- Les tests de cycle de vie couvrent l'annulation et la suppression du groupe de processus complet au timeout.

## Installation neuve isolée

Une seconde pile a été créée avec le projet Compose `ai-factory-socket-fresh`, un workspace dédié et tous les
autres volumes vierges. Le démarrage a construit les images, initialisé les stockages, puis rendu sains
l'orchestrateur, le MCP sandbox et les quatre runners sans socket Docker, `group_add` ni `DOCKER_SOCKET_GID`.
La pile temporaire a ensuite été arrêtée avec succès et ses seuls volumes ont été supprimés. La pile de
développement originale a été redémarrée sur ses volumes préservés.

## Limites de cette preuve

Cette qualification valide le backend Compose local et ses cinq profils. Elle ne vaut pas qualification GKE,
ne remplace pas la campagne de sécurité sur cluster et ne clôt pas le test du workflow applicatif complet avec
arrêt propre de toute la pile.
