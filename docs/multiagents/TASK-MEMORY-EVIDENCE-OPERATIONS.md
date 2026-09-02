# Exploitation de Task Memory et Evidence MCP

## Sources d'autorité

| Donnée | Autorité | Usage |
|---|---|---|
| État, timers, signaux, DAG et chronologie | Historique Temporal | Reprise du workflow et décisions de coordination |
| Vue API/UI | Projection PostgreSQL | Lecture rapide, reconstruisible, sans contenu sensible |
| Plans, patches, rapports, reviews et manifeste | Evidence MCP | Artefacts immuables, digests, classification et audit |
| Travail temporaire | Workspace ou worktree | Calcul recréable, jamais preuve ni source de reprise |
| Code livré | SCM | Commit et Pull Request après approbation |

`TaskMemory` est le port applicatif de consultation et de transition des tâches. L'adaptateur mémoire sert au
prototype et aux tests ; il ne devient pas une nouvelle source d'autorité. Le modèle PostgreSQL cible conserve
la projection et les métadonnées corrélées, tandis que Temporal et Evidence MCP restent nécessaires pour une
reconstruction vérifiable.

## Écriture normale

1. Le workflow émet une transition déterministe avec `task_id`, `workflow_run_id`, `attempt_id`,
   `source_commit` et, selon le cas, `delegation_id` ou `agent_run_id`.
2. Une Activity idempotente stocke le contenu utile par `evidence.store`. Le contenu est chiffré et immuable ;
   un même identifiant avec un contenu divergent est refusé.
3. Le workflow conserve uniquement l'URI, le digest, le type, la classification et le statut vérifié.
4. La projection applique l'événement avec verrouillage optimiste et identifiant source idempotent.
5. Avant approbation, `evidence.create_manifest` assemble l'ensemble exact de preuves et de décisions de
   politique. L'approbation porte sur `manifest_id` et son digest.

Une écriture partielle, un statut autre que `COMPLETE`, une référence étrangère à la tâche/tentative ou un
digest divergent bloque la consolidation et la livraison.

## Lecture et audit

- Les agents ordinaires reçoivent des résumés bornés par `evidence.get_summary`, sans contenu brut.
- `evidence.read` est réservé au workflow, à l'Independent Reviewer et aux usages humains audités.
- Toute lecture brute indique acteur, tâche, tentative et motif ; l'adaptateur recalcule le digest localement.
- Autorisations, refus, approbations et changements de mode sont inscrits dans un journal chaîné et vérifiable.
- Les logs, traces et métriques ne doivent contenir ni contenu d'artefact, ni prompt complet, ni secret.
- Une URI seule ne fait pas preuve : tâche, tentative, commit, classification, statut et digest sont revérifiés.

## Rétention et purge

La politique exécutable est `resources/multiagents/policies/artifact-lifecycle-policy-v1.yaml`.

| Famille | Rétention |
|---|---:|
| plan, patch, intégration, tests | 90 jours |
| évaluation, métadonnées, Sonar | 180 jours |
| SBOM, Trivy, review, approbation, manifeste | 365 jours |
| journal d'audit immuable | 730 jours |

Les artefacts `trivy`, `review`, `approval` et `manifest` sont `CONFIDENTIAL`; les autres familles sont
`INTERNAL`. Le chiffrement AES-256-GCM lie en AAD la version de schéma, la tâche, la tentative, le type et le
digest. En cible, les clés viennent de Secret Manager ; elles ne sont jamais stockées avec l'artefact.

Ordre obligatoire d'une purge : vérifier l'absence de legal hold, purger le contenu chiffré dans Evidence MCP,
écrire une tombstone limitée au digest, attendre le délai de grâce de 30 jours, puis purger les métadonnées de
projection. Un legal hold exige un acteur autorisé, un motif, une échéance et une trace d'audit. Toute erreur
arrête la purge et déclenche une alerte.

## Sauvegarde

Une sauvegarde exploitable réunit, sur une fenêtre cohérente :

- la persistance et les métadonnées du namespace Temporal, y compris la rétention active ;
- PostgreSQL métier et ses migrations, bien que la projection soit reconstruisible ;
- les objets Evidence MCP, leurs versions, métadonnées, digests et tombstones ;
- les références aux clés de chiffrement et leur procédure de restauration, sans exporter les secrets en clair ;
- les catalogues, contrats, prompts et politiques Git correspondant aux workflows sauvegardés.

Le contrôle de sauvegarde vérifie le nombre d'objets, l'accès aux clés, l'intégrité des digests et la capacité à
restaurer dans un environnement isolé. Une copie de PostgreSQL seule n'est pas un plan de reprise.

## Restauration et reconstruction

1. Geler les nouvelles soumissions et conserver les services dégradés en lecture seule.
2. Restaurer les versions de code, contrats, politiques et workers capables de rejouer les historiques.
3. Restaurer Temporal et vérifier namespace, task queues, workflows ouverts, timers, signaux et versions
   épinglées avant de reprendre le polling.
4. Restaurer Evidence MCP et les clés ; échantillonner puis vérifier les digests, classifications et statuts.
5. Recréer un schéma de projection vide avec les migrations correspondant au code restauré.
6. Lancer `ProjectionRebuilder` depuis les événements Temporal et les résumés Evidence MCP revérifiés.
7. Comparer compteurs de tâches, runs, délégations, décisions, budgets et preuves avec les sources.
8. Reprendre d'abord les workflows en attente humaine, puis un canary interne ; rouvrir les soumissions après
   validation Exploitation et Sécurité.

La reconstruction remplace la projection de façon atomique. Une preuve manquante ou altérée conserve la
projection précédente et classe la tâche en échec fermé ; elle n'est jamais régénérée silencieusement avec un
nouveau digest.

## Scénarios d'incident

| Incident | Réponse |
|---|---|
| Projection PostgreSQL perdue | reconstruire depuis Temporal + résumés Evidence ; ne pas redémarrer les tâches |
| Evidence indisponible | suspendre consolidation, approbation et livraison ; conserver les workflows en attente |
| Artefact altéré ou absent | isoler la tâche, révoquer l'approbation liée, préserver journaux et déclencher l'incident sécurité |
| Historique Temporal indisponible | ne pas déduire l'état depuis l'UI ; restaurer Temporal avant toute commande métier |
| Workspace perdu | le recréer depuis `source_commit` et les artefacts vérifiés, avec un nouvel environnement isolé |
| Clé de chiffrement indisponible | considérer les preuves illisibles ; restaurer la clé selon la procédure de secrets |

## Preuves de contrôle

Les tests `ProjectionRebuilderTest`, `McpEvidenceRepositoryTest`, `EvidenceApprovalGateTest`,
`TaskMemorySchemaTest`, `LegacyTaskMigratorTest` et `HashChainedSecurityAuditJournalTest` couvrent la frontière
de stockage, la reconstruction, les digests, l'audit et la compatibilité des tâches terminées. Un exercice
d'exploitation réel doit en plus archiver date, versions, volume restauré, écarts, temps observés et signatures
des responsables ; les tests unitaires ne valent pas preuve de PRA.
