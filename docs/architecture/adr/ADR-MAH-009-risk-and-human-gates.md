# ADR-MAH-009 — Classes de risque et décisions humaines préalables

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : admission, délégation, génération de patch et effets externes

## Contexte

Le mode d'exécution ne suffit pas à autoriser une tâche. Une même orchestration peut traiter une correction locale
réversible ou une évolution d'IAM, de données ou de production. La classe de risque doit donc être calculée par
l'hôte, bornée par une politique versionnée et réévaluée lorsqu'un impact nouveau apparaît.

## Décision

Le `WorkflowCoordinator` applique
[`resources/multiagents/policies/risk-policy-v1.yaml`](../../../resources/multiagents/policies/risk-policy-v1.yaml).
Les agents proposent des impacts structurés et leurs preuves ; ils ne choisissent ni leur classe effective, ni
l'approbateur, ni le franchissement d'une porte.

La classe effective est le maximum entre la classe initiale calculée par l'hôte et les impacts vérifiés découverts
pendant l'exécution. Une classe ne peut jamais diminuer au sein d'une tentative.

## Classes

| Classe | Définition | Exemples | Traitement maximal |
|---|---|---|---|
| `R0` | Analyse ou changement sans comportement de production | documentation, commentaires, fixtures isolées | automatique selon le mode |
| `R1` | Changement local, réversible, sans frontière sensible | correctif borné dans un module, tests associés | automatique selon le mode |
| `R2` | Changement transverse ou de contrat maîtrisé | contrat compatible, dépendance approuvée, schéma strictement additif | hiérarchique actif avec gate avant effet |
| `R3` | Changement sensible exigeant une décision experte | authentification, autorisation, secret, IAM, réseau, migration de données, CI/CD | décision humaine avant Code |
| `R4` | Action irréversible, directe en production ou hors mandat | suppression de données, déploiement direct, élargissement de privilège non borné | refusée par l'automatisation |

Un critère de classe supérieure prévaut. Une information requise absente ou contradictoire entraîne
`HUMAN_TRIAGE`, jamais une classification optimiste.

## Matrice par mode

| Mode | `R0` | `R1` | `R2` | `R3` | `R4` |
|---|---|---|---|---|---|
| `PIPELINE` | automatique | automatique | décision avant effet | décision avant Code | refus |
| `HIERARCHICAL_SHADOW` | analyse | analyse | analyse | analyse sans patch ni effet | refus |
| `HIERARCHICAL_CANARY` | automatique | automatique | triage humain | triage humain | refus |
| `HIERARCHICAL_ACTIVE` | automatique | automatique | décision avant effet | décision avant Code | refus |

L'autorisation d'analyser en shadow ne constitue pas une autorisation de produire, d'appliquer ou de livrer un
patch. Les entrées sensibles restent soumises aux mêmes règles de minimisation et de redaction.

## Portes humaines

### Porte `BEFORE_CODE`

Elle est franchie avant toute délégation au `CodeAgent` ou création de patch lorsque la tâche implique :

- authentification, autorisation, IAM, secrets, chiffrement ou exposition réseau ;
- migration ou suppression de données, données personnelles ou changement de propriétaire de données ;
- changement non compatible d'un contrat public ;
- infrastructure, CI/CD, chaîne de confiance, politique de sécurité ou permissions d'outils ;
- choix d'architecture matériel non résolu ou extension du scope, du budget ou des capacités demandées.

### Porte `BEFORE_EXTERNAL_EFFECT`

Elle est franchie après les gates déterministes et la revue indépendante, avant :

- merge ou écriture sur une branche protégée ;
- publication d'artefact, release, déploiement ou modification d'infrastructure ;
- exécution d'une migration ou mutation de données partagées ;
- rotation de secret, modification IAM ou activation d'une configuration sensible.

Pour `R2`, cette seconde porte est obligatoire. Pour `R3`, les deux portes sont obligatoires et portent des
approbations distinctes si les objets approuvés diffèrent.

## Objet d'approbation

Une approbation est limitée à `task_id`, tentative, commit source, classe, mode, scope, action, digest de l'objet
présenté, identité et rôle de l'approbateur, décision, justification et expiration. Tout changement de digest,
scope, classe ou tentative invalide l'approbation. L'absence, l'expiration ou l'ambiguïté vaut refus.

Les approbateurs minimaux sont Produit pour le scope et le comportement, Sécurité pour `R3` sécurité, Données
pour les migrations et Plateforme pour IAM, réseau, CI/CD, infrastructure ou production. Une même identité ne
peut pas produire le changement et constituer l'unique approbation lorsque la séparation des responsabilités est
requise.

## Réévaluation et arrêt

Chaque résultat spécialiste restitue les impacts découverts. Le coordinateur recalcule la classe avant une
nouvelle délégation et avant chaque effet. Une hausse de risque suspend le workflow, invalide les autorisations
insuffisantes et émet une demande humaine structurée. `R4`, approbation incohérente ou impact impossible à
classer provoquent un arrêt fermé et audité.

## Conséquences

- le risque devient une décision déterministe et explicable ;
- une approbation générale de ticket ne vaut pas autorisation illimitée ;
- le multi-agent peut analyser plus largement en shadow sans acquérir de capacité d'effet ;
- les contrats des lots suivants doivent transporter classe, impacts, gates et références d'approbation.
