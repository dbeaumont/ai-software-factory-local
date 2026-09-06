# A2A-111 — Qualification de la supply chain du runtime A2A

## Décision

Le runtime générique A2A est soumis à une gate bloquante avant publication. Une image n'est qualifiable que par
une référence immuable `IMAGE@sha256:...` et si toutes les preuves suivantes sont produites puis validées :

- SBOM CycloneDX JSON et SPDX JSON avec licences et empreintes ;
- rapport de licences conforme à l'allow-list et sans licence interdite ou inconnue ;
- rapport Trivy `vuln,secret` sans vulnérabilité `HIGH` ou `CRITICAL` ;
- signature keyless Cosign issue par le workflow GitHub Actions autorisé ;
- attestation Cosign de provenance SLSA v1 ;
- manifeste SHA-256 de l'ensemble des preuves.

La politique versionnée est `resources/a2a/supply-chain-policy-v1.json`. Elle épingle également le SDK A2A
`1.1.0.Final`, ses deux artefacts runtime obligatoires, les images de base par digest et l'utilisateur non-root
`10001`. Toute exception de vulnérabilité nécessite une approbation RSSI explicite ; le script de qualification
n'accepte aucune exception implicite.

## Commandes

Validation déterministe de la politique et de ses cas négatifs :

```shell
scripts/test-a2a-supply-chain-policy.sh
ruby scripts/verify-a2a-supply-chain-policy.rb
```

Qualification d'une image publiée et signée :

```shell
make a2a-supply-chain A2A_RUNTIME_IMAGE=registry.example/runtime@sha256:<digest>
```

Les preuves sont écrites par défaut dans `.local/a2a-supply-chain/`, hors Git. La gate de cutover A2A-180 devra
référencer leur manifeste exact et non un tag mutable.

## Preuves d'implémentation du 6 septembre 2026

- Les tests positifs et négatifs de politique passent, notamment le refus d'une licence GPL et d'une dérive de
  version du SDK.
- L'image locale `ai-factory-a2a-agent-runtime:a2a-111` a été construite intégralement avec JDK 25 ; 63 tests
  (`agent-core` et `a2a-agent-runtime`) passent sans échec.
- L'image résultante s'exécute avec l'UID `10001`, conserve les labels OCI de révision, source, licence et digest
  de base, et son manifeste local est `sha256:b17ff16bbd0a6efd8f3c7cc3ae6d6f0db88c6330d685a7464c216fcb0b70d92e`.

La signature, la provenance et le scan Trivy final restent volontairement attachés à l'image publiée par digest :
un artefact local non publié ne peut pas satisfaire cette partie de la gate.
