# MCP-008 — Conventions de nommage et de versionnement MCP

> Statut : convention normative pour les nouveaux contrats  
> Portée : serveurs, outils, ressources, schémas, champs, profils, erreurs et artefacts de déploiement  
> Mots normatifs : `DOIT`, `NE DOIT PAS`, `DEVRAIT`, `PEUT`

## 1. Principes

1. Un nom décrit une capacité métier, jamais son implémentation technique ou son fournisseur actuel.
2. Un nom publié est stable. Un changement de sens crée une nouvelle capacité ou une nouvelle version majeure de contrat.
3. Les noms, versions, URI et profils proviennent d'un registre versionné ; un ticket, dépôt, modèle ou résultat d'outil ne peut pas les définir.
4. Le nom d'un outil ne porte pas sa version. La version est exprimée par le contrat et les métadonnées serveur.
5. Un profil versionné est immuable : tout changement de commande, image, ressources, secret ou politique réseau crée un nouvel identifiant.
6. Les identifiants restent lisibles en ASCII et ne contiennent ni secret, ni donnée personnelle, ni nom de tenant.

## 2. Serveurs MCP

### 2.1 Nom logique

Format :

```text
<domaine>[-<qualificatif>]-mcp
```

Règles : minuscules ASCII, segments `kebab-case`, expression régulière `^[a-z][a-z0-9]*(?:-[a-z0-9]+)*-mcp$`, 63 caractères maximum. L'environnement, la région et la version ne figurent pas dans le nom logique.

| Capacité | Nom logique canonique | Module source |
|---|---|---|
| contexte dépôt | `repository-context-mcp` | `apps/mcp/repository-context-server/` |
| exécution sandbox | `sandbox-execution-mcp` | `apps/mcp/sandbox-execution-server/` |
| livraison SCM | `scm-delivery-mcp` | `apps/mcp/scm-delivery-server/` |
| assurance | `assurance-mcp` | `apps/mcp/assurance-server/` |
| preuves | `evidence-mcp` | `apps/mcp/evidence-server/` |

Les identifiants de connexion client peuvent omettre le suffixe, par exemple `repository-context`, mais la propriété MCP `server.name`, le nom applicatif et la clé du registre utilisent le nom logique complet.

### 2.2 Version serveur

La version serveur suit SemVer strict : `MAJOR.MINOR.PATCH`, sans préfixe `v` dans `server.version`.

- `MAJOR` : rupture de compatibilité de protocole exposé, suppression de contrat ou changement de sémantique incompatible ;
- `MINOR` : nouvel outil/capacité optionnelle ou comportement compatible ;
- `PATCH` : correction sans modification du contrat public.

Une version de production est identifiée par le triplet :

```text
server.name + server.version + image_digest
```

Le tag d'image est informatif ; le déploiement et l'audit utilisent le digest immuable. Les versions `0.x` restent admises pendant le prototype et ne sont pas considérées stables pour des consommateurs externes.

## 3. Outils

### 3.1 Format

```text
<namespace>.<verbe>_<objet>[_<qualificatif>]
```

- namespace et segments en minuscules ASCII ;
- namespace séparé par un point, opération en `snake_case` ;
- expression régulière `^[a-z][a-z0-9]*\.[a-z][a-z0-9]*(?:_[a-z0-9]+)*$` ;
- 64 caractères maximum ;
- aucun suffixe `v1`, nom de fournisseur (`gitea`, `sonar`) ou environnement (`prod`) ;
- verbes précis : `get`, `list`, `read`, `search`, `resolve`, `validate`, `apply`, `run`, `evaluate`, `compare`, `register`, `verify`, `create`, `cancel` ;
- `execute`, `invoke`, `run_command`, `write_file` et les outils shell/génériques sont interdits.

Namespaces réservés : `context`, `sandbox`, `scm`, `assurance`, `evidence`.

Exemples canoniques :

```text
context.list_tree
context.read_file
sandbox.validate_patch
sandbox.get_execution
scm.create_draft_pull_request
assurance.evaluate_quality_gate
evidence.get_manifest
```

Le nom décrit une seule intention. Une transaction dont l'atomicité est une propriété de sécurité conserve un nom métier unique, par exemple `scm.create_draft_pull_request`, plutôt que plusieurs outils bas niveau.

### 3.2 Évolution

- Une modification compatible conserve le nom et peut accompagner une version mineure du serveur.
- Une modification du type/obligation d'un champ, du niveau d'effet ou de la sémantique exige une nouvelle version majeure de schéma.
- Si l'intention métier change, un nouveau nom est créé et l'ancien est déprécié.
- Un outil déprécié reste disponible pendant la fenêtre N/N-1 annoncée, émet un warning structuré et possède une date de retrait ; aucun alias silencieux n'est ajouté.

## 4. Ressources et URI

Les schemes sont minuscules et réservés par type de serveur. Une URI logique est opaque hors de son serveur : elle n'est ni une URL HTTP, ni une instruction d'egress.

| Type | Template canonique | Propriété |
|---|---|---|
| contenu dépôt immuable | `repo://{task_id}/{source_commit}/{path}` | `repository-context-mcp` |
| preuve immuable | `evidence://{task_id}/{attempt_id}/{artifact_type}/{sha256}` | `evidence-mcp` |
| exécution sandbox | handle `execution_id`, pas d'URI publique | `sandbox-execution-mcp` |

Règles :

- les segments réservés sont ASCII et les segments variables sont percent-encodés une seule fois ;
- le `path` dépôt reste relatif, sans `.`/`..`, séparateur alternatif ni symlink sortant ;
- un SHA Git est un hexadécimal minuscule complet de 40 caractères dans le contrat actuel ;
- un SHA-256 est un hexadécimal minuscule de 64 caractères ;
- query et fragment sont interdits en v1 ;
- identifiants, credentials, hostname réel, chemin absolu et URL de staging ne figurent jamais dans une URI présentée à un agent ;
- toute résolution répète l'autorisation sur principal, tâche, tentative et tenant ; l'URI seule n'accorde aucun droit.

## 5. JSON Schema et fichiers de contrat

### 5.1 Format des fichiers

Format outil :

```text
<namespace>-<operation>-<direction>-v<major>.schema.json
```

avec `direction` égal à `request` ou `result`. Les points et underscores du nom d'outil deviennent des tirets.

Format concept partagé :

```text
<concept>-v<major>.schema.json
```

Exemples :

```text
context-list-tree-request-v1.schema.json
context-list-tree-result-v1.schema.json
sandbox-get-execution-request-v1.schema.json
sandbox-execution-result-v1.schema.json
common-envelope-v1.schema.json
error-v1.schema.json
```

Tous les fichiers résident dans `resources/mcp/schemas/`, utilisent JSON Schema Draft 2020-12 et déclarent :

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ai-factory.local/mcp/schemas/<filename>",
  "title": "<tool-or-concept> <direction> v<major>"
}
```

### 5.2 Version de contrat

- `schema_version` est une chaîne contenant le majeur décimal, actuellement `"1"`.
- Une version majeure publiée est immuable. Avec la validation stricte (`additionalProperties: false` ou `unevaluatedProperties: false`), toute modification de forme exige une nouvelle version majeure.
- Un serveur PEUT accepter N et N-1 simultanément ; il sélectionne explicitement le schéma par `schema_version`.
- Une version inconnue produit `INVALID_ARGUMENT` ou `INCOMPATIBLE_SCHEMA`, jamais une conversion implicite.
- Chaque release serveur publie le digest SHA-256 exact des schémas servis. Le client compare ce manifeste à ses schémas locaux avant `READY`.
- Le draft JSON Schema et la révision du protocole MCP sont des axes distincts du `schema_version` métier.

Les fichiers v1 déjà présents sans segment `request` sont des artefacts de prototype. MCP-009 les normalise avant publication du registre ; aucune dépendance externe ne doit se construire sur ces noms transitoires.

## 6. Champs, identifiants et enums

| Élément | Convention | Exemples |
|---|---|---|
| propriétés JSON | `snake_case` | `task_id`, `source_commit`, `safe_message` |
| identifiant opaque | suffixe `_id` | `task_id`, `attempt_id`, `execution_id` |
| digest | suffixe `_digest` | `patch_digest`, `image_digest` |
| timestamp | suffixe `_at`, RFC 3339 UTC | `started_at`, `completed_at` |
| durée/deadline | timestamp `deadline` ou entier avec unité dans le nom | `deadline`, `retry_after_ms` |
| URI | suffixe `_uri` | `evidence_uri` |
| booléen | préfixe `is_`, `has_`, `can_` si ambigu | `is_truncated` |
| enum wire | `UPPER_SNAKE_CASE` | `INDETERMINATE`, `POLICY_DENIED` |
| rôle | `lower-kebab-case` | `patch-repair` |
| profile ID | `lower-kebab-case-v<major>` | `quality-sonar-v1` |
| idempotency key | opaque, 16–256 caractères | produite par l'orchestrateur, jamais interprétée comme chemin |

Un identifiant n'est jamais réutilisé pour une autre nature d'objet. Les valeurs humaines (`display_name`, titre de PR) sont séparées des identifiants techniques.

## 7. Profils et politiques

Format d'un profil :

```text
<capacité>[-<moteur>]-v<major>
```

Exemples : `patch-check-v1`, `test-maven-v1`, `quality-sonar-v1`, `security-syft-trivy-v1`.

Un profil est immuable sur : opération, image/digest, commande interne, CPU, mémoire, PIDs, timeout, volumes, secrets autorisés et politique réseau. Tout changement de l'un de ces éléments crée un nouvel identifiant de profil, même si l'interface de l'outil ne change pas.

Les fichiers de politique suivent `<objet>-v<major>.yaml`, par exemple `tool-permissions-v1.yaml`. La propriété racine `version` utilise la même valeur majeure sous forme de chaîne.

## 8. Erreurs, états et verdicts

Les codes d'erreur sont stables en `UPPER_SNAKE_CASE` et ne contiennent ni nom d'exception Java, ni backend, ni version :

```text
INVALID_ARGUMENT
UNAUTHORIZED
FORBIDDEN
NOT_FOUND
CONFLICT
POLICY_DENIED
LIMIT_EXCEEDED
DEPENDENCY_UNAVAILABLE
TIMEOUT
INDETERMINATE
INCOMPATIBLE_SCHEMA
INTERNAL
```

Les états techniques et verdicts métier sont distincts :

- état d'exécution : `ACCEPTED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` ;
- verdict : `PENDING`, `PASSED`, `REJECTED`, `INDETERMINATE`.

Un `safe_message` est destiné au client. Les détails internes possèdent un identifiant de corrélation et restent dans les journaux protégés.

## 9. Compatibilité, dépréciation et retrait

| Changement | Version outil/schéma | Version serveur minimale | Politique |
|---|---|---|---|
| correction interne sans effet observable | inchangée | PATCH | tests de non-régression |
| nouvel outil optionnel | nouveau nom, schéma v1 | MINOR | capacité annoncée à la négociation |
| nouveau profil immuable | nouvel ID `-vN` | MINOR | ancien profil maintenu pendant migration |
| modification de champ ou enum | schéma majeur suivant | MAJOR si ancien contrat retiré | support N/N-1 et canary |
| changement de niveau d'effet/sémantique | nouvel outil ou schéma majeur | MAJOR | nouvelle revue sécurité/permissions |
| suppression d'un outil | ancienne version dépréciée puis retirée | MAJOR | télémétrie d'usage, date et rollback |

Procédure :

1. publier le nouveau schéma/profil immuable et ses golden tests ;
2. déployer le serveur capable de N et N-1 ;
3. vérifier négociation, permissions et résultats en canary/shadow ;
4. migrer les clients ;
5. confirmer l'absence d'usage de N-1 ;
6. retirer N-1 dans une version majeure avec rollback par digest d'image.

## 10. Écarts observés et suivi de normalisation

| Écart initial | Cible | État |
|---|---|---|
| ancien `context-list-tree-v1.schema.json` | `context-list-tree-request-v1.schema.json` | normalisé par MCP-009 |
| ancien `context-read-file-v1.schema.json` | `context-read-file-request-v1.schema.json` | normalisé par MCP-009 |
| ancien `context-search-code-v1.schema.json` | `context-search-code-request-v1.schema.json` | normalisé par MCP-009 |
| ancien `sandbox-start-execution-v1.schema.json` | `sandbox-start-execution-request-v1.schema.json` | nom normalisé par MCP-009 |
| ancien `sandbox-get-execution-v1.schema.json` | `sandbox-get-execution-request-v1.schema.json` | normalisé par MCP-009 |
| `attempt_id` absent des DTO/arguments runtime | obligatoire dans les contrats v1 liés à une tentative | schéma terminé par MCP-009 ; propagation runtime MCP-026 |
| profils tests regroupés dans `test-auto-v1` | `test-maven-v1`, `test-gradle-v1`, `test-node-v1` | MCP-071/MCP-076 |
| version `0.1.0` des serveurs | acceptable POC ; passage à `1.0.0` après gate de compatibilité | MCP-016/MCP-024 |

## 11. Critères de clôture de MCP-008

- [x] Les noms canoniques des cinq serveurs et namespaces sont fixés.
- [x] Les formats des outils, ressources, schémas, champs, profils et erreurs sont définis.
- [x] Les axes de version serveur, contrat, protocole, profil et image sont distingués.
- [x] Les règles N/N-1, dépréciation, retrait et rollback sont décrites.
- [x] Les écarts des artefacts existants sont associés aux jalons de normalisation.
