# Ajouter un agent A2A

Cette procédure est obligatoire pour tout nouveau rôle. Ajouter uniquement un conteneur ou une Agent Card ne
suffit pas : le rôle doit être gouverné par le catalogue, adressé par Temporal et qualifié de bout en bout avant
d’être admis.

## 1. Identité et gouvernance

- [ ] Ajouter le rôle dans `resources/agents/catalog-v1.yaml` avec `kind`, parent, owner, autonomie, outils,
  `mayDelegateTo`, contrat principal et gate humaine.
- [ ] Ajouter `resources/agents/<role>.yaml`; le manifest ne doit accorder aucun outil ou délégation absent du
  catalogue.
- [ ] Ajouter le prompt `resources/prompts/<role>.md`, versionné et sans secret.
- [ ] Ajouter une identité unique dans `resources/a2a/service-identities-v1.json` : client OAuth2, sujet SPIFFE et
  référence de credential ne sont partagés avec aucun autre rôle.
- [ ] Ajouter les origines HTTPS exactes du rôle aux profils `compose` et `gke` de
  `resources/a2a/agent-registry-v1.json`.

## 2. Contrats et Agent Card

- [ ] Ajouter ou réutiliser des schémas JSON métier fermés (`additionalProperties: false`) pour chaque entrée et
  sortie ; versionner toute rupture de compatibilité.
- [ ] Ajouter chaque couple rôle/entrée et l’unique sortie principale dans
  `resources/a2a/skill-contract-map-v1.json`.
- [ ] Vérifier que l’ID du skill suit `<role>.<input-contract>` et que les types de média restent bornés à JSON ou
  à une référence Evidence.
- [ ] Générer la carte depuis le catalogue, la signer avec une clé propre au rôle et ajouter seulement
  l’empreinte publique au trust store de l’orchestrateur.
- [ ] Refuser le démarrage si carte, manifest, catalogue, contrats, outils, délégations ou endpoint divergent.

## 3. Temporal et déploiement

- [ ] Ajouter un nœud de contrôle au DAG ; le runtime ne crée jamais lui-même une délégation réseau.
- [ ] Utiliser le child workflow de contrôle A2A avec un `messageId` déterministe et la task queue
  `a2a-agent-<role>-v1`.
- [ ] Déclarer un service Compose et un Deployment/Service GKE par rôle, basés sur l’image générique
  `a2a-agent-runtime` et `AI_FACTORY_AGENT_ROLE=<role>`.
- [ ] Monter certificat, clé, CA, clé de carte et jetons du seul rôle ; ne monter ni socket Docker, ni credential
  SCM, ni datasource de l’orchestrateur.
- [ ] Appliquer les NetworkPolicies minimales : orchestrateur → A2A ; runtime → Temporal, LLM et seuls MCP
  accordés ; runtime → callback orchestrateur pour les notifications.
- [ ] Configurer readiness, liveness, limites de ressources, concurrence, quota tenant et arrêt gracieux.

## 4. Observabilité et exploitation

- [ ] Émettre spans et métriques A2A avec rôle, skill, état, workflow et identifiants bornés ; ne jamais exporter
  prompt, résultat brut, jeton ou contenu Evidence.
- [ ] Ajouter le rôle aux dashboards et alertes SigNoz, puis vérifier les liens vers les runbooks.
- [ ] Documenter propriétaire, SLO, quota, procédure de rotation, symptômes de saturation et retrait du rôle.

## 5. Qualification avant admission

- [ ] Tests unitaires : manifest, isolation du rôle, contrats, permissions, erreurs et métriques.
- [ ] Tests de contrat : enveloppe, carte signée, entrée/sortie et références Evidence.
- [ ] TCK A2A : toutes les opérations et capacités annoncées, zéro échec.
- [ ] Intégration : envoi, résultat, callback perdu, `tasks/get`, redémarrage, annulation et idempotence concurrente.
- [ ] Sécurité : rôle/skill/tenant croisés, carte forgée, SSRF, rejeu, taille, injection et secrets.
- [ ] Temporal : replay, timeout ambigu, retry, `continue-as-new`, drainage et rollback vers une release A2A.
- [ ] Performance : latence, mémoire, débit et backpressure sous les seuils approuvés.
- [ ] Mettre à jour la gate de cutover avec commit, images digestées, SBOM, rapports et approbateurs.

Commandes minimales :

```shell
make a2a-config
make test-a2a-security
make test-a2a-compose-integration
make test-a2a-performance
```

Archiver ensuite ces sorties dans la gate `docs/qualification/a2a/GATE-A2A-180-CUTOVER.md`. Le rôle n’est
admissible que lorsque cette gate est ouverte. Une indisponibilité ou une incohérence suspend les
nouvelles admissions ; elle ne sélectionne jamais un runtime Java local ou un transport de secours.
