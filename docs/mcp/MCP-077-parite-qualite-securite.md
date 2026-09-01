# MCP-077 — Parité qualité et sécurité

Date de qualification : 1er septembre 2026.

## Périmètre

Le serveur `sandbox-execution-mcp` porte temporairement les deux opérations historiques de `SandboxService` :

- `sandbox.run_quality`, profil immuable `quality-sonar-v1` ;
- `sandbox.run_security`, profil immuable `security-syft-trivy-v1`.

Le client ne fournit ni commande, ni image, ni réseau, ni variable d'environnement. Les identifiants de profils, commandes, limites et secrets restent sous le contrôle du serveur. Les sorties sont bornées, redacted, persistées avec leur digest et restituées par `sandbox.get_execution`.

## Correction d'egress

Les scanners de sécurité ont besoin de leurs bases de données sans bénéficier d'un accès Internet général. Le profil de sécurité rejoint donc le réseau interne `factory` et reçoit uniquement les variables proxy contrôlées par le serveur. La liste Squid autorise les dépôts nécessaires :

- `toolbox-data.anchore.io` pour Syft ;
- `mirror.gcr.io` et `ghcr.io` pour la base Trivy ;
- les destinations déjà autorisées pour les dépendances de build.

Les mises à jour applicatives opportunistes de Syft sont désactivées et la sortie de progression Trivy est neutralisée. Aucun secret n'est transmis dans la requête MCP ou persisté dans le manifeste.

## Preuve runtime

La campagne a été exécutée directement via le protocole MCP sur le workspace Maven `3392f03d`, au commit immuable `3ddff5310c53a19614101aa1b4888827807ed9d3`.

| Opération | Exécution | État | Verdict | Preuve |
|---|---|---|---|---|
| `sandbox.run_security` | `9d2606b90c9a4bd2be8bcd48c3776230` | `SUCCEEDED` | `PASSED` | SBOM CycloneDX produit, base Trivy téléchargée via `mirror.gcr.io`, zéro vulnérabilité détectée, digest de sortie `443ec63554e93aafff9b2c927f4f9515bf25af6343cb33d1ea4e638d98afb482` |
| `sandbox.run_quality` | `8c36c727468b4916ace0bb4e592730a7` | `SUCCEEDED` | `PASSED` | analyse SonarQube acceptée, quality gate `PASSED`, digest de sortie `ed24dfb563bb715d94d806b4224a0171ae397d830aaa58341d2b7a473a3328e1` |

Dans les deux cas, `evidence_status=COMPLETE`, l'exit code vaut `0` et le résultat final est obtenu par le handle opaque retourné au démarrage du job.

## Vérifications automatisées

La suite Maven du serveur vérifie notamment :

- la sélection exclusive des profils enregistrés ;
- les réseaux et variables proxy associés au profil sécurité ;
- l'absence de commande ou d'environnement injecté par l'appelant ;
- les limites de ressources, la suppression des capabilities et `no-new-privileges` ;
- la séparation entre état technique, verdict métier et complétude de preuve.

Commande validée :

```text
mvn -q -s /tmp/ai-factory-mcp-settings.xml -f apps/mcp/sandbox-execution-server/pom.xml clean test
```

## Conclusion

MCP-077 est accepté. Les deux opérations reproduisent le comportement utile du chemin direct avec une frontière de privilège plus stricte. La comparaison shadow multi-cas reste suivie séparément par MCP-087 ; elle ne remet pas en cause la preuve fonctionnelle et de sécurité de ces profils.
