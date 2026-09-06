# Runbook — rollback A2A avec Temporal conservé

## Détection

Appliquer ce runbook lors d'une régression A2A confirmée, d'une violation de sécurité, d'un doublon, d'une
divergence étendue ou d'un dépassement SLO qui ne peut être corrigé en place.

## Confinement immédiat

Geler toutes les nouvelles admissions A2A et les effets externes inconnus. Temporal reste l'unique orchestrateur :
le rollback ne réactive jamais les anciens appels directs/in-process et ne change pas l'autorité des historiques.

## Diagnostic

Inventorier commit, digest d'image, Agent Cards, build IDs Temporal, rôles, files, tâches, associations et Evidence
affectés. Vérifier le replay des historiques représentatifs avec la dernière version A2A qualifiée.

```bash
make a2a-status
make a2a-config
make a2a-pki
```

## Rétablissement

Le rollback cible obligatoirement une release qui utilise déjà A2A. Les trois identifiants suivants forment une
unité immuable et sont tirés de la dernière gate approuvée :

- `AI_FACTORY_ORCHESTRATOR_IMAGE`, référence orchestrateur avec digest `@sha256:` ;
- `AI_FACTORY_A2A_RUNTIME_IMAGE`, référence commune des runtimes avec digest `@sha256:` ;
- `AI_FACTORY_TEMPORAL_BUILD_ID`, Build ID d'origine de cette release.

1. Vérifier que les deux références d'image contiennent un digest et que le Build ID n'a jamais été réaffecté.
2. Conserver task store, associations, outbox, Evidence, workspace et namespace Temporal ; ne lancer ni `clean`,
   ni `down --volumes`, ni restauration partielle.
3. Garder les pollers du build incident actif pour ses workflows épinglés, sauf si son exécution aggrave
   l'incident ; dans ce cas, stopper ses effets mais préserver le déploiement et les historiques.
4. Exporter les trois variables vers la dernière release A2A qualifiée et redéployer sans reconstruction :

   ```bash
   export AI_FACTORY_ORCHESTRATOR_IMAGE='registry.example/orchestrator@sha256:<digest>'
   export AI_FACTORY_A2A_RUNTIME_IMAGE='registry.example/a2a-runtime@sha256:<digest>'
   export AI_FACTORY_TEMPORAL_BUILD_ID='<build-id-immuable>'
   docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full \
     up --detach --no-build orchestrator a2a-task-db \
     a2a-supervisor a2a-architecture-agent a2a-impact-analysis a2a-dependencies-contracts \
     a2a-code-agent a2a-developer a2a-patch-repair a2a-test-agent a2a-test-design \
     a2a-test-evidence a2a-security-agent a2a-threat-model a2a-security-findings \
     a2a-independent-reviewer
   ```

5. Enregistrer le build restauré auprès du même déploiement Temporal, sans rendre un historique incompatible
   éligible à ce build. Ne changer la version courante qu'après présence de toutes les task queues attendues.
6. Réconcilier chaque effet à issue inconnue par `messageId`, `taskId` et digest d'artefact avant de reprendre un
   retry. Un état absent ou ambigu maintient les admissions fermées.
7. Vérifier toutes les Agent Cards, readiness, pollers, associations et preuves, puis rouvrir les admissions en
   une fois. Il n'existe aucun redémarrage rôle par rôle des admissions dans la bascule franche.

La commande de rollback utilise `--no-build` : elle ne doit jamais fabriquer silencieusement une nouvelle image
sous un tag historique. Une image manquante provoque un arrêt et une escalade.

## Vérification et clôture

Exiger tous les pollers/readiness, backlog décroissant, replay déterministe, corrélations bijectives, zéro doublon,
SLO sous budget et alertes saines pendant vingt minutes. Comparer les digests réellement déployés à la gate de
rollback, archiver la réconciliation de chaque tâche active et conserver les anciens workers jusqu'au drainage.
L'approbation Exploitation est obligatoire avant toute réouverture.

## Escalade

Escalader à `agent-platform`, Temporal et Sécurité. Une perte de données suit les procédures de restauration
Temporal/A2A ; ne jamais restaurer une seule base indépendamment des autres autorités.
