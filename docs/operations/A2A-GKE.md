# Exploiter la flotte A2A sur GKE

## Périmètre et autorités

Ce runbook couvre les quatorze runtimes A2A privés de `ai-factory-agents`. Temporal demeure l'autorité de
l'orchestration, le task store A2A celle de l'état protocolaire et Evidence celle des artefacts immuables. Une
opération Kubernetes ne doit jamais réécrire directement ces états ni réactiver un appel d'agent en mémoire.

Les manifests de référence sont sous `infrastructure/gke/a2a`. `agents.generated.yaml` est généré : toute
modification durable passe par `scripts/generate-a2a-gke-manifests.rb`, jamais par une édition manuelle du fichier.

## Pré-requis plateforme

Avant le premier déploiement, vérifier :

- un cluster GKE privé et VPC-native, avec Pod Security `restricted` et DNS fonctionnel entre les namespaces ;
- les namespaces `ai-factory-control`, `ai-factory-services` et `ai-factory-observability`, avec les labels
  attendus par les `NetworkPolicy` ;
- Temporal, sa namespace `ai-factory`, le déploiement de workers et les task queues A2A ;
- un proxy PostgreSQL privé portant `app.kubernetes.io/name=a2a-task-db-proxy` et le schéma migré ;
- Secrets Store CSI Driver avec le provider GCP et Workload Identity, sans fichier de clé de compte de service ;
- les secrets Secret Manager TLS/CA/CRL, JWK de carte et jeton MCP de chaque rôle, plus le Secret Kubernetes
  `a2a-runtime-database` ;
- Repository Context MCP, Evidence MCP et la gateway OTLP dans leurs namespaces privés ;
- l'adaptateur de métriques externes qui expose `temporal_task_queue_backlog` au HPA ;
- les images runtime et orchestrateur signées, vérifiées et référencées par digest `@sha256:`.

Le rôle plateforme remplace `PROJECT_ID`, `REGISTRY` et le digest factice dans un overlay de release contrôlé. Le
manifeste source avec placeholders ne doit jamais être appliqué tel quel.

## Rendu et contrôles avant mutation

Vérifier que le généré, le catalogue de découverte, les permissions MCP et la fermeture des entrées publiques sont
cohérents :

```shell
ruby scripts/generate-a2a-gke-manifests.rb infrastructure/gke/a2a/agents.generated.yaml --check
ruby scripts/verify-a2a-gke-manifests.rb
ruby scripts/verify-a2a-gke-discovery.rb
ruby scripts/verify-a2a-private-entry.rb
```

Puis rendre l'overlay de release matérialisé et demander une validation serveur, sans appliquer :

```shell
A2A_GKE_OVERLAY=<chemin-overlay-de-release-a2a>
kubectl kustomize "$A2A_GKE_OVERLAY"
kubectl apply --dry-run=server -k "$A2A_GKE_OVERLAY"
```

Archiver le YAML rendu, son SHA-256, les digests d'images et le résultat du dry-run dans la preuve de release.

## Déploiement

L'ordre est obligatoire afin que les runtimes échouent fermés plutôt que de démarrer partiellement :

1. fermer les admissions et inventorier les workflows/tâches actifs ;
2. sauvegarder Temporal par son API/outillage supporté, le task store A2A, les associations, Evidence et la
   configuration signée — sans lire ni modifier les tables internes Temporal ;
3. vérifier PostgreSQL, migrations, Temporal, MCP, OTLP, Secret Manager/CSI et métrique externe ;
4. appliquer les policies d'admission, le namespace, la configuration, puis les workloads A2A ;
5. attendre les quatorze Deployments, sans rouvrir les admissions ;
6. publier la découverte fermée dans l'orchestrateur et vérifier cartes, pollers et notifications ;
7. exécuter le smoke de release, puis seulement la gate de réouverture.

Commandes de contrôle :

```shell
kubectl apply -k <overlay-de-release-a2a>
kubectl -n ai-factory-agents get deploy,pod,svc,hpa,pdb
kubectl -n ai-factory-agents get deploy -l app.kubernetes.io/name=a2a-agent-runtime -o name | \
  xargs -n1 kubectl -n ai-factory-agents rollout status --timeout=10m
kubectl -n ai-factory-agents wait pod -l app.kubernetes.io/name=a2a-agent-runtime \
  --for=condition=Ready --timeout=10m
```

Exiger exactement quatorze rôles distincts, au moins deux replicas prêts par rôle, uniquement des Services
`ClusterIP`, toutes les cartes signées et compatibles, au moins un poller sur chaque task queue et zéro divergence
Temporal/A2A. Une readiness verte seule ne suffit pas à rouvrir les admissions.

## Scaling et capacité

Chaque rôle possède un HPA indépendant : minimum 2, maximum 10, cible moyenne de backlog Temporal à 5 et fenêtre
de stabilisation de scale-down de 300 secondes. Les PDB imposent `minAvailable: 1` et le rolling update
`maxUnavailable: 0`.

```shell
kubectl -n ai-factory-agents get hpa -w
kubectl -n ai-factory-agents top pod -l app.kubernetes.io/name=a2a-agent-runtime
kubectl -n ai-factory-agents describe hpa a2a-developer
kubectl -n ai-factory-agents get events --sort-by=.lastTimestamp
```

Ne pas utiliser `kubectl scale` comme réglage permanent : le HPA le réconciliera. Modifier min/max ou concurrence
seulement dans un overlay versionné, après test de charge, et vérifier simultanément pools PostgreSQL, quotas
tenant, slots Temporal, limites LLM/MCP et SLO. En cas de saturation, appliquer
[A2A-SATURATION](runbooks/A2A-SATURATION.md) avant toute hausse.

## Rotation des certificats, cartes et secrets

Une rotation est un changement coordonné, avec période de chevauchement :

1. publier la nouvelle CA ou clé publique dans les bundles de confiance et JWK vérifiés ;
2. créer de nouvelles versions Secret Manager pour `tls.crt`, `tls.key`, `ca.crt`, `ca.crl`, `card-jwks.json` et,
   si prévu, le jeton MCP ;
3. attendre la synchronisation CSI, puis redémarrer un rôle non critique ;
4. vérifier handshake mTLS, SAN, issuer, audience, `kid`, signature de carte, scopes et accès MCP ;
5. dérouler rôle par rôle avec `maxUnavailable: 0`, puis redémarrer/valider l'orchestrateur ;
6. attendre l'expiration des caches de cartes et le drainage des connexions utilisant l'ancien matériel ;
7. révoquer l'ancien certificat, publier la CRL et retirer l'ancienne clé publique ;
8. archiver uniquement serials, `kid`, dates et digests — jamais une clé ou un jeton.

```shell
kubectl -n ai-factory-agents rollout restart deployment/a2a-developer
kubectl -n ai-factory-agents rollout status deployment/a2a-developer --timeout=10m
kubectl -n ai-factory-agents describe pod -l ai-factory.io/agent-role=developer
```

Une clé compromise impose la révocation immédiate et le runbook
[A2A-CERTIFICAT-EXPIRE](runbooks/A2A-CERTIFICAT-EXPIRE.md). Une carte invalide impose
[A2A-CARTE-INVALIDE](runbooks/A2A-CARTE-INVALIDE.md). Ne jamais désactiver mTLS, CRL, signature ou allowlist pour
achever une rotation.

## NetworkPolicies et exposition

Le namespace applique un default-deny aux runtimes. Les seuls flux communs admis sont : orchestrateur vers A2A
sur 8090 ; runtimes vers DNS, Temporal 7233, proxy PostgreSQL 5432 et gateway OTLP 4317. Les flux MCP sont générés
par rôle d'après la matrice versionnée. Aucun runtime ne possède de LoadBalancer, NodePort, ExternalIP, Ingress,
Gateway ou HTTPRoute.

```shell
kubectl -n ai-factory-agents get networkpolicy -o wide
kubectl -n ai-factory-agents get svc -o custom-columns=NAME:.metadata.name,TYPE:.spec.type,EXTERNAL-IP:.spec.externalIPs
kubectl get validatingadmissionpolicy,validatingadmissionpolicybinding | grep a2a
```

Après chaque changement de rôle ou d'outil, relancer les vérificateurs GKE et un test positif/négatif depuis un
Pod de diagnostic autorisé : le rôle doit atteindre uniquement ses MCP déclarés. Toute proposition d'exposition
publique exige un ADR et un threat model distincts ; la policy `a2a-routes-forbidden` doit continuer à refuser le
manifeste tant qu'ils ne sont pas approuvés.

## Incidents

Commencer par fermer les admissions du rôle affecté, conserver les workloads et collecter les preuves :

```shell
kubectl -n ai-factory-agents get pod -o wide
kubectl -n ai-factory-agents logs deployment/a2a-developer --all-pods --since=30m
kubectl -n ai-factory-agents describe deployment/a2a-developer
kubectl -n ai-factory-agents get events --sort-by=.lastTimestamp
```

Corréler dans Cloud Monitoring/Trace/Logging avec `agent_role`, `agent_skill`, `task_state`, `rpc_operation`,
`result`, `trace_id`, les identifiants de tâche et l'historique Temporal. Ne jamais ajouter les IDs de tâche aux
dimensions métriques. Choisir ensuite le runbook spécialisé :

- [agent indisponible](runbooks/A2A-AGENT-INDISPONIBLE.md) ;
- [callback perdu](runbooks/A2A-CALLBACK-PERDU.md) ;
- [tâche bloquée](runbooks/A2A-TACHE-BLOQUEE.md) ;
- [divergence d'état](runbooks/A2A-DIVERGENCE-ETAT.md) ;
- [saturation](runbooks/A2A-SATURATION.md).

Un redémarrage ne remplace pas la réconciliation. Pour une issue inconnue, relire `tasks/get`, l'association,
l'historique d'agent et Evidence avant tout retry.

## Drainage d'une version Temporal

Conserver chaque ancien Build ID tant que des workflows y sont épinglés. Exporter la description du déploiement
avec le CLI Temporal autorisé dans le réseau privé, puis appliquer la vérification fail-closed :

```shell
temporal worker deployment describe \
  --address temporal.ai-factory-control.svc.cluster.local:7233 \
  --namespace ai-factory --name ai-factory-orchestrator --output json \
  > /tmp/a2a-worker-deployment.json
ruby scripts/verify-a2a-worker-drainage.rb <ancien-build-id> /tmp/a2a-worker-deployment.json
```

Le retrait est interdit si le build est courant, en ramp-up, absent ou dans un état autre que `drained`. Après
validation, archiver la description et son digest, obtenir l'approbation d'exploitation, puis retirer le worker ;
ne jamais supprimer l'historique Temporal.

## Rollback

Le rollback revient uniquement à une release déjà A2A, compatible avec les historiques et le schéma courant. Il
ne réactive jamais les appels directs. Avant tout redéploiement :

1. fermer les admissions et ouvrir un incident ;
2. inventorier toutes les tâches, sauvegarder les quatre autorités et réconcilier les issues ambiguës ;
3. compléter la gate `A2A-206` avec les digests d'images, Build IDs, inventaire et preuves exacts ;
4. faire vérifier la gate avec `ruby scripts/verify-a2a-rollback-gate.rb <gate.json>` ;
5. appliquer l'overlay de la dernière release A2A approuvée, référencée par digests ;
6. conserver les workers de l'incident pour leurs historiques jusqu'au drainage ;
7. vérifier cartes, pollers, readiness, tâches, preuves, absence de doublon et SLO avant réouverture.

La procédure normative et les limites irréversibles sont dans
[ROLLBACK-A2A](runbooks/ROLLBACK-A2A.md) et [A2A-ROLLBACK-COMPATIBILITY](A2A-ROLLBACK-COMPATIBILITY.md). Une gate
absente ou rejetée maintient les admissions fermées.

## Preuves de clôture

Pour chaque déploiement, rotation, incident, drainage ou rollback, conserver : commit, overlay rendu et digest,
digests/signatures d'images, versions publiques des secrets, cartes et digests, inventaire des tâches, description
des versions Temporal, sorties rollout/readiness/pollers, liens de traces, réconciliation, fenêtre SLO et identité
des approbateurs. Exclure systématiquement secrets, jetons, clés privées et contenu métier sensible.
