# Formation aux responsabilités multi-agents

## Objectif et statut

Ce support prépare la formation des équipes Produit, Architecture, Sécurité, Développement et Exploitation à
l'architecture 04. La tâche de formation n'est terminée qu'après tenue des sessions, exercices réussis et
archivage des attestations ; la présence de ce document seule ne vaut pas formation.

## Tronc commun — 60 minutes

À l'issue du tronc commun, chaque participant doit pouvoir expliquer :

- la différence entre `PIPELINE`, shadow, canary et active ;
- pourquoi le Supervisor propose alors que le workflow décide et porte les effets ;
- la hiérarchie des agents et l'indépendance du Reviewer ;
- le rôle de Temporal, Task Memory, Evidence MCP et de la projection PostgreSQL ;
- les contrats, scopes, budgets, gates déterministes et permissions deny-by-default ;
- le lien entre manifeste, digest, revue indépendante, approbation humaine et PR ;
- les critères d'arrêt, le kill switch et le retour obligatoire par le shadow après incident.

Exercice commun : à partir d'un ticket R2 multi-domaine, identifier le chemin, les spécialistes, les preuves,
les effets réservés au workflow et trois conditions imposant un échec fermé.

## Modules par fonction

### Produit et Risk — 45 minutes

Responsabilités : qualité du besoin, critères d'acceptation, propriété des décisions Produit, politique de revue
indépendante, acceptabilité du risque résiduel et approbation des changements à impact client.

Exercice : traiter une `DIVERGENT_RECOMMENDATION`, comparer deux options liées au même digest, refuser une
approbation périmée et expliquer pourquoi un gate technique ne peut pas être dérogé par une opinion.

Validation : décision complète, acteur légitime, preuves consultées, option autorisée et digest exact.

### Architecture — 45 minutes

Responsabilités : impacts, dépendances, API/données, compatibilité, scopes, ADR, règles de routage et validation
des contrats Architecture.

Exercice : classifier un changement de schéma public, produire les contraintes des scopes Code et arbitrer un
`INCOMPATIBLE_SCOPE` sans accorder d'outil supplémentaire à un agent.

Validation : impacts et décisions humaines explicites, scope testable et évolution N/N-1 documentée.

### Sécurité — 60 minutes

Responsabilités : threat model, findings, classification des preuves, permissions sensibles, legal hold,
incidents d'isolation/secret/preuve et approbation de reprise correspondante.

Exercice : détecter une tentative d'escalade d'outil, actionner un kill switch de rôle, préserver les preuves,
révoquer une approbation liée à un digest divergent et définir les conditions de reprise.

Validation : confinement au niveau correct, aucun effet répété, secrets renouvelés et retour en shadow.

### Développement et Quality Engineering — 60 minutes

Responsabilités : scopes de code disjoints, qualité et maintenabilité des patches, stratégie de tests, couverture
des critères, analyse des résultats fournis et absence de verdict sans preuve complète.

Exercice : répartir deux modules en worktrees isolés, détecter une collision, faire intervenir Patch Integrator,
puis refuser `PASSED` face à un résultat tronqué ou indéterminé.

Validation : aucun fichier hors scope, même commit source, digests vérifiés et gates rejoués sur le consolidé.

### Exploitation — 75 minutes

Responsabilités : Temporal/workers, task queues, versions épinglées, SLO, canary, capacité, kill switch, rollback,
restauration et réconciliation des effets par idempotence.

Exercice : simuler une indisponibilité Temporal et une preuve altérée ; geler les admissions, classer les effets
en vol, restaurer les services, reconstruire la projection et reprendre en shadow.

Validation : aucun historique/preuve supprimé, aucun effet dupliqué, files en drainage et approbations obtenues.

## Exercice transverse de crise — 90 minutes

Scénario : un canary Code parallèle déclenche une collision, un job sandbox répond tardivement et le digest d'une
preuve ne correspond plus au manifeste approuvé.

Résultats attendus :

1. Développement identifie la collision et bloque l'intégration.
2. Sécurité classe la divergence de preuve et demande le confinement.
3. Exploitation met le canary à zéro, active le kill switch adapté et réconcilie le job par `execution_id`.
4. Architecture vérifie scope, contrat et besoin de replan.
5. Produit constate l'invalidité de l'approbation et ne la réutilise pas.
6. L'équipe conserve les artefacts, crée une nouvelle tentative et ne reprend qu'en shadow.

## Critères de réussite

Chaque fonction doit obtenir au moins 80 % au questionnaire commun, réussir son exercice métier et participer à
l'exercice transverse. Toute erreur consistant à contourner un gate, élargir une permission pendant l'incident,
répéter un effet inconnu, supprimer une preuve ou reprendre directement en active est éliminatoire et impose une
nouvelle session.

## Preuves à archiver

Pour chaque session : date, environnement, version du support, formateur, participants et fonctions, résultats
du questionnaire, résultat de l'exercice, écarts/actions, échéances et approbation du responsable. Les preuves
ne contiennent pas de ticket, secret, prompt ou artefact sensible en clair.

Le dossier de clôture MAH-337 doit réunir au moins une session validée pour chacune des cinq fonctions, le compte
rendu de l'exercice transverse et les actions résiduelles closes ou formellement acceptées.

## Références de travail

- `docs/archive/releases/1.2.0-archi-04/ETAT-PROTO-1.2.0.md` ;
- `docs/architecture/agents/CATALOGUE-AGENTS-V1.md` et `CYCLE-DE-VIE-AGENT.md` ;
- `docs/qualification/multi-agents/policies-and-operations/ROUTAGE-REPLANS-CONTRADICTIONS.md` ;
- `docs/qualification/multi-agents/policies-and-operations/TASK-MEMORY-EVIDENCE-OPERATIONS.md` ;
- `docs/operations/runbooks/README.md` et `CANARY-KILL-SWITCH-INCIDENT.md`.
