# Runbook — canary, kill switch et reprise après incident

## Préconditions du canary

Ne pas ouvrir le canary tant que l'un des éléments suivants manque :

- verdict `QUALIFIED` portant sur les digests exacts du code, modèle, prompts, contrats, outils et politiques ;
- approbations Produit, Architecture, Sécurité et Exploitation ;
- dépôts, équipes, classes de risque et rôles explicitement allow-listés ;
- baseline appariée, télémétrie coût/latence/qualité/sécurité complète et dashboards actifs ;
- kill switch testé, `PIPELINE` disponible et exercice de rollback archivé ;
- responsables de palier et d'incident joignables pendant toute la fenêtre d'observation.

Un canary ne sert pas à produire les preuves qui manquent au gate de qualification. Il valide en conditions
réelles un candidat déjà qualifié.

## Progression

Chaque phase utilise un périmètre stable, un commit source connu et un bucket déterministe. Le passage de palier
est une décision explicite, jamais une conséquence automatique du temps écoulé.

1. **Shadow** : exécuter le DAG sans influencer la baseline ; comparer routage, résultats, coût et durée.
2. **Lecture seule** : activer Supervisor, Architecture, Test Design et Threat Model ; conserver un Developer et
   toutes les actions déterministes du pipeline.
3. **Consolidation active** : utiliser les sorties spécialisées et activer Test Evidence, Security Findings et
   Independent Reviewer ; maintenir l'approbation humaine.
4. **Code parallèle borné** : autoriser au plus deux Developer sur des scopes prouvés disjoints ; désactiver le
   parallélisme à la première collision ou dérive de coût.
5. **Extension** : élargir dépôt par dépôt, équipe par équipe et classe de risque par classe de risque.

À chaque phase, commencer au plus petit périmètre convenu puis augmenter selon les paliers du dossier de
changement. Une nouvelle version de modèle, prompt, outil, contrat ou politique invalide l'observation en cours
et impose un retour au shadow avec une nouvelle qualification.

## Contrôles de palier

Avant toute extension, archiver :

- nombre de tâches et paires comparables, taux de succès, routage inutile et bénéfice multi-domaine ;
- tests, quality gates, findings, preuves invalides, appels refusés et effets tentés ;
- tokens, coût fournisseur, durée, compute sandbox, temps humain et saturation des files ;
- contradictions, replans, interventions humaines, retries et réponses tardives ;
- liste des incidents et état de leurs actions ;
- versions/digests déployés et décision signée du responsable de palier.

Toute donnée absente vaut `INCOMPLETE`. Un gate déterministe échoué, une violation d'isolation ou un effet non
autorisé déclenche le rollback immédiat, sans attendre la fin de la fenêtre.

## Actionner le kill switch

L'orchestrateur relit avant chaque appel le fichier indiqué par `AI_FACTORY_MCP_KILL_SWITCH_FILE`. Il n'expose
aucune API d'écriture ; en cible, seul le compte d'exploitation modifie le montage en lecture seule.

**Précondition non satisfaite par le Compose courant :** la variable n'est pas transmise et aucun fichier de
contrôle n'est monté. Le canary ne doit pas être ouvert avant l'ajout de ce montage, de sa procédure atomique et
d'un test opérateur. Dans le POC actuel, arrêter/isoler le service concerné reste le confinement disponible.

```properties
revision=incident-<identifiant-unique>
global.disabled=false
servers.disabled=
tools.disabled=
roles.disabled=security-agent
modes.disabled=HIERARCHICAL_CANARY,HIERARCHICAL_ACTIVE
role-modes.disabled=
```

Choisir le niveau le plus étroit qui contient sûrement l'incident : couple rôle/mode, rôle, outil, serveur, mode,
puis global. Chaque modification porte une nouvelle `revision`. Un fichier présent mais illisible ou sans
`revision` coupe tous les appels ; ne pas le réparer en le supprimant sans comprendre l'incident.

Après modification :

1. vérifier dans les métriques/journaux que les nouveaux appels sont refusés avec la bonne révision ;
2. stopper les nouvelles admissions hiérarchiques et mettre la qualification à `INCOMPLETE` si le candidat est
   en cause ;
3. inventorier les workflows et effets en vol ;
4. préserver fichier de contrôle, logs, historiques, preuves et configuration comme pièces d'incident.

## Rollback

Appliquer [ROLLBACK-MULTI-AGENTS.md](ROLLBACK-MULTI-AGENTS.md). L'ordre sûr est : plafond d'admission
`PIPELINE`, qualification `INCOMPLETE`, canary à zéro, arrêt des nouveaux workflows hiérarchiques, gel des
effets non confirmés, suspension/annulation des activités enfants, puis routage des nouvelles tâches vers la
baseline.

Un effet demandé dont l'issue est inconnue est réconcilié par sa clé d'idempotence auprès du système cible. Il
n'est jamais relancé pour « tester ». Une nouvelle tentative pipeline possède un nouvel `attempt_id` et ne
réutilise pas une approbation de la tentative hiérarchique.

## Reprise après incident

La reprise nécessite : cause racine, ensemble des tâches affectées, effets réconciliés, intégrité état/preuves,
correctif versionné, tests de régression, exercice de rollback réussi et rapport de qualification courant.

1. Déployer le correctif dans un environnement isolé et rejouer le scénario d'incident.
2. Vérifier contrats, permissions, digests, idempotence, compatibilité/replay Temporal et absence de secret.
3. Obtenir l'approbation Exploitation ; ajouter Sécurité pour isolation, permission, secret ou preuve, et Produit
   pour tout impact fonctionnel ou client.
4. Réactiver uniquement `HIERARCHICAL_SHADOW` sur un périmètre réduit.
5. Retirer le kill switch le plus étroit après observation stable ; conserver les barrières plus larges jusqu'à
   validation de leur périmètre.
6. Refaire tous les paliers. Le retour direct à `HIERARCHICAL_ACTIVE` est interdit.

## Clôture

La clôture archive l'incident, les versions et révisions, la chronologie, les tâches/effets concernés, les
métriques avant/après, les preuves de correction et rollback, ainsi que les approbations. Un état inconnu, une
preuve invérifiable ou un effet non réconcilié interdit la clôture et toute remontée de mode.
