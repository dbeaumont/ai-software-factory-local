# Compatibilité et limites irréversibles du rollback A2A

## Objet

Ce document définit quand un rollback binaire A2A est sûr et quand une transformation ou une migration en avant est
obligatoire. Temporal reste l'autorité d'orchestration pendant toute l'opération. Un retour vers les anciens appels
Java directs n'est jamais une option de rollback.

Une release n'est une cible de rollback valide que si elle sait relire tous les historiques Temporal encore ouverts,
toutes les lignes déjà écrites et toutes les versions de contrats, cartes et références Evidence encore actives.
L'existence d'une image antérieure ne constitue donc pas, à elle seule, une preuve de compatibilité.

## Socle actuellement écrit

| Surface persistante ou échangée | Version/plancher actuel | Autorité |
|---|---:|---|
| Protocole et binding A2A | `1.0` JSON-RPC | Agent Card et runtime A2A |
| Enveloppe de délégation | `a2a-envelope-v1`, `schema_version=1` | orchestrateur |
| Extension d'exécution | `execution-context-v1`, `schemaVersion=1` | orchestrateur |
| Résultat et référence Evidence | `a2a-result-v1`, `a2a-artifact-reference-v1` | agent + Evidence |
| Catalogue rôle/skill/contrat | `skill-contract-map-v1`, `catalog_version=1` | artefact orchestrateur |
| Projection PostgreSQL agent | Flyway `V001` à `V005` | runtime A2A |
| Workflow d'agent | `AgentTaskWorkflowV1`, comportement `PINNED` | Temporal |
| Workflow orchestrateur | workflows et activités versionnés dans l'historique | Temporal |
| URI Evidence | `evidence://<task>/<attempt>/<type>/<sha256>` immuable | Evidence MCP |

Le triplet de release reste indissociable : digest de l'image orchestrateur, digest de l'image runtime A2A et Build
ID Temporal. Les cartes signées, le catalogue de contrats et les migrations applicables doivent être archivés avec
ce triplet.

## Matrice de réversibilité

| Changement observé depuis la cible | Rollback binaire direct | Condition ou traitement obligatoire |
|---|---|---|
| Ajout d'une table ou colonne ignorée par l'ancien code | possible sous conditions | migration expand-only, aucun trigger ni invariant nouveau requis par l'ancien binaire |
| Écriture d'une donnée que l'ancien code ne consomme pas | interdit tant qu'elle est active | drainer ou fournir un lecteur compatible dans la cible |
| Suppression/renommage de table, colonne, index d'unicité ou contrainte | interdit | restaurer un schéma compatible dans un clone, transformer, puis faire une migration en avant |
| Réduction de taille, changement de type ou réinterprétation d'un champ | interdit | prouver une conversion sans perte et la valider hors production |
| Nouvel état A2A ou changement de sémantique d'un état existant | interdit | dual-read explicite dans les deux versions et campagne de replay |
| Nouveau champ de contrat | interdit par défaut | les schémas v1 sont fermés ; publier une nouvelle version et maintenir un adaptateur dual-version |
| Retrait/renommage de rôle, skill ou contrat | interdit avec tâche ouverte | drainer toutes les tâches concernées ou restaurer la carte et le worker capables de les servir |
| Changement d'URL, issuer, audience ou clé de signature de carte | possible seulement avec chevauchement | ancienne et nouvelle chaîne de confiance simultanément valides pendant le drainage |
| Changement non déterministe d'un workflow Temporal | interdit | conserver son Build ID ou utiliser les mécanismes Temporal de versionnement avant déploiement |
| Suppression d'un Build ID ayant un historique ouvert | interdit | remettre ses pollers et attendre le drainage ou migrer explicitement les workflows |
| Réécriture/suppression d'une Evidence ou modification de son digest | irréversible | aucune conversion en place ; restaurer l'objet immuable exact depuis une sauvegarde cohérente |
| Révocation ou destruction d'une clé, d'un certificat ou d'un secret | irréversible | émettre une nouvelle identité ; ne jamais tenter de restaurer le secret révoqué |
| Changement de tenant, ACL ou identité de workload | interdit sans preuve | migration autorisée, auditée et liée aux objets/digests concernés |

## Plancher particulier de la migration V005

`V005__add_a2a_cancellation_outbox.sql` est expand-only au niveau SQL, mais les lignes qu'elle contient ont une
sémantique active. Dès qu'une ligne non acquittée existe dans `a2a_agent_cancellation_outbox`, la cible doit savoir :

1. relire cette ligne ;
2. renvoyer idempotemment le signal au workflow `a2a-agent-task-v1/<rôle>/<task>` ;
3. acquitter la ligne seulement après acceptation du signal Temporal.

Un runtime antérieur à cette capacité est donc une cible invalide, même s'il démarre correctement sur le schéma
V005. Le test `make test-a2a-rollback-load` qualifie une cible compatible avec ce plancher ; il ne certifie pas un
binaire historique arbitraire.

## Contrats et Agent Cards

Les schémas A2A v1 ont une validation fermée. Une évolution additive n'est pas présumée compatible : elle reçoit un
nouvel identifiant/version, une fixture canonique, un mapping rôle/skill/contrat et une période dual-read. Durant
cette période, la carte annonce uniquement les versions réellement supportées par le runtime correspondant.

Une tâche conserve la carte et le digest de carte utilisés à l'admission. Modifier une carte ne réinterprète jamais
une tâche existante. Si la cible de rollback ne sert plus le skill ou le contrat épinglé, ses admissions restent
fermées et l'ancien worker compatible continue de drainer.

## Historiques Temporal

Le replay réussi de fixtures ne suffit pas si un historique ouvert contient une commande introduite après la cible.
Avant rollback, inventorier par Build ID et type de workflow : historiques ouverts, activités en vol, timers,
signaux, tâches en attente d'entrée et annulations. Chaque historique doit avoir au moins un worker compatible et
son task queue d'origine.

Les workflows d'agents sont `PINNED`. Ne jamais réaffecter un Build ID, forcer un historique vers un code antérieur,
renommer une queue pour masquer l'incompatibilité ni supprimer les pollers avant drainage.

## Décision de rollback

Le rollback binaire est **bloqué** si au moins une des conditions suivantes est vraie :

- une version de schéma, de contrat, de carte ou de workflow présente n'est pas lisible par la cible ;
- une outbox de notification ou d'annulation ne peut pas être consommée par la cible ;
- une tâche active référence un rôle, un skill, une audience, une clé ou un endpoint absent de la cible ;
- un historique Temporal ouvert échoue au replay avec le Build ID cible ;
- une Evidence attendue est absente, mutable ou ne correspond plus à son digest ;
- une transformation nécessite suppression, troncature, réinterprétation ou changement d'ACL ;
- les sauvegardes des autorités Temporal, PostgreSQL, Evidence et projections ne forment pas un point cohérent.

Dans ces cas, conserver les admissions fermées. Choisir soit une cible plus récente compatible, soit une migration
en avant. Toute transformation est exécutée sur une restauration isolée, vérifiée par comptages et digests, puis
soumise à la gate opérateur ; elle n'est jamais improvisée pendant le rollback.

## Preuves minimales à joindre à la gate

- matrice source/cible remplie pour chaque surface du socle ;
- résultats du replay Temporal par type et Build ID ;
- compteurs avant/après des tâches, messages, associations, deux outbox et artefacts ;
- liste des contrats/cartes rencontrés et preuve de support par la cible ;
- résultat de `make test-a2a-rollback-load` et de l'exercice avec les images digestées ;
- inventaire des Evidence avec vérification des URI et SHA-256 ;
- état des sauvegardes cohérentes, décisions et approbateur d'exploitation.
