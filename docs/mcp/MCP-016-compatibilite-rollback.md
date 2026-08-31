# MCP-016 — Compatibilité N/N-1 et rollback

> Statut : plan défini, répétition opérationnelle à réaliser avant promotion
>
> Politique machine-readable : `resources/mcp/policies/compatibility-rollback-policy-v1.yaml`

## 1. Portée des versions

Les versions suivantes évoluent indépendamment et doivent être enregistrées ensemble dans les preuves d'un run :

| Couche | Version | Règle |
|---|---|---|
| protocole MCP | révision négociée par le SDK | client et serveur doivent négocier une révision commune |
| serveur | SemVer | image promue par digest, jamais par tag mutable |
| outil et schéma | majeure explicite | une rupture crée une nouvelle majeure |
| profil sandbox | suffixe `-vN` | commande, image, ressources ou réseau modifiés créent un nouveau profil |
| état persistant | version de snapshot | lecteur compatible N et N-1 avant toute écriture N |
| politiques | fichier `-vN` | digest associé au run et conservé avec les preuves |

La version courante des contrats est N=`1`. Il n'existe donc pas encore de contrat N-1 déployé. La règle devient active lors de l'introduction de la v2 : tout serveur v2 doit accepter les requêtes v2 et v1 pendant la fenêtre de migration.

## 2. Contrat de compatibilité

- Un changement additif conserve la majeure : nouveau champ de réponse optionnel, nouvel outil optionnel ou nouveau code d'erreur documenté.
- Le client ignore les champs de réponse optionnels inconnus mais refuse une réponse dont les champs obligatoires ou invariants ne sont pas vérifiables.
- Le serveur refuse explicitement une majeure de requête inconnue avec `INCOMPATIBLE_SCHEMA`; il ne devine pas la sémantique attendue.
- Une suppression, un changement de type, une nouvelle valeur qui modifie une décision, ou un changement d'effet exige une nouvelle majeure.
- Les profils sandbox sont immuables. `test-auto-v2` coexiste avec `test-auto-v1`; le sens de `test-auto-v1` n'est jamais modifié silencieusement.
- Les snapshots et preuves déjà écrits ne sont ni réécrits ni rétrogradés pendant un rollback.

## 3. Déploiement N/N-1

1. Publier les schémas N, le catalogue et les golden tests.
2. Déployer un serveur capable de lire N et N-1, encore alimenté par des clients N-1.
3. Exécuter les tests protocole, contrats, permissions et lecture d'états N/N-1.
4. Activer le client N en `MCP_SHADOW`, puis sur un canary sans tâche critique.
5. Comparer verdicts, preuves, erreurs et SLO avant d'élargir.
6. Migrer tous les clients autorisés vers N.
7. Maintenir le chevauchement au moins 28 jours et constater zéro usage N-1 pendant 14 jours.
8. Retirer N-1 seulement après revue et répétition réussie du rollback.

Le serveur est déployé avant le client lorsqu'il s'agit d'ajouter N. Pour retirer N-1, l'ordre est inversé : tous les clients quittent N-1 avant le retrait serveur.

## 4. Déclencheurs de rollback

Le canary est arrêté et les nouvelles opérations à effet sont gelées si l'un de ces événements apparaît :

- échec de négociation ou contrat `INCOMPATIBLE_SCHEMA` sur un flux auparavant valide ;
- fast burn d'un SLO MCP ;
- faux succès, digest incohérent ou preuve incomplète acceptée ;
- régression d'autorisation, d'isolation ou de redaction ;
- perte d'idempotence d'une opération à effet ;
- impossibilité de relire ou réconcilier un état persistant N-1.

Les lectures peuvent être routées automatiquement vers le dernier digest promu si les contrats restent compatibles. La reprise des opérations à effet exige une validation humaine.

## 5. Procédure de rollback

1. **Geler** les nouvelles mutations et conserver tâches, handles, clés d'idempotence, logs et digests.
2. **Qualifier** le périmètre : serveur, versions, schémas, profils, état persistant et runs concernés.
3. **Sélectionner** le dernier ensemble promu d'images, schémas et politiques par digest vérifié.
4. **Router** le trafic compatible vers ce digest sans réactiver un appel direct depuis l'orchestrateur.
5. **Réconcilier** chaque opération en vol avec son handle et sa clé d'idempotence ; ne jamais resoumettre aveuglément un effet.
6. **Vérifier** readiness, négociation, golden tests N/N-1, accès inter-tâches, digests et smoke test sans mutation.
7. **Rouvrir** les lectures, puis observer les métriques.
8. **Rouvrir** les mutations uniquement après approbation et vérification des effets distants.
9. **Documenter** l'incident, le digest restauré, les tâches touchées et le critère de nouvelle promotion.

Le rollback ne peut ni contourner une approbation, ni remplacer une preuve absente par un succès, ni remonter durablement la socket Docker ou les secrets dans l'orchestrateur.

## 6. Particularités par serveur

| Serveur | État | Point de rollback |
|---|---|---|
| `repository-context-mcp` | stateless, lecture seule | retour au digest précédent après tests N/N-1 ; aucun état à migrer |
| `sandbox-execution-mcp` | snapshots et jobs en vol | ancien contrôleur doit relire N et N-1 ; réconciliation par `execution_id` et idempotency key |
| `scm-delivery-mcp` futur | effets Gitea | vérifier branche/commit/PR distants avant tout retry |
| `assurance-mcp` futur | verdicts structurés | conserver les preuves brutes et réévaluer sans les modifier |
| `evidence-mcp` futur | stockage immuable | lecture multi-version ; aucune down-migration destructive |

Si l'ancien binaire ne peut pas lire un état écrit par N, le rollback binaire est interdit. N reste disponible en mode lecture/réconciliation jusqu'au déploiement d'un lecteur dual ; aucune conversion destructive n'est autorisée dans l'urgence.

## 7. Checklist de répétition

- [ ] Les images N et N-1 sont disponibles par digest et vérifiées.
- [ ] Le client N réussit contre les serveurs N et N-1 autorisés.
- [ ] Le client N-1 réussit contre le serveur N pendant la fenêtre de chevauchement.
- [ ] Une majeure inconnue échoue avec `INCOMPATIBLE_SCHEMA`.
- [ ] Les snapshots N-1 sont relus par N et les snapshots N par le lecteur de rollback prévu.
- [ ] Un job en vol est retrouvé, pas dupliqué, après changement de version.
- [ ] Les gates de sécurité et d'approbation restent bloquants.
- [ ] Les métriques et preuves identifient versions et digests avant/après rollback.

Les tests d'incompatibilité détaillés appartiennent à MCP-035, les tests protocole systématiques à MCP-224 et le canary/rollback automatisé par digest à MCP-225.
