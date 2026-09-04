# MCP-011 — Limites par défaut

> Statut : baseline normative v1  
> Politique machine-readable : `resources/mcp/policies/default-limits-v1.yaml`  
> Principe : un appelant peut demander moins, jamais plus

## 1. Objectif

Cette politique borne la consommation de l'usine à tous les niveaux : transport MCP, lecture du dépôt, sandbox, boucle agentique, livraison SCM et conservation des preuves. Elle protège la disponibilité, le budget, l'isolation entre tâches et la lisibilité des résultats.

Les valeurs v1 sont des plafonds initiaux pour le prototype. Leur évolution exige une nouvelle version de politique, des tests de charge et une validation Produit/Sécurité lorsque l'impact financier ou d'exposition augmente.

## 2. Règles de priorité

L'ordre d'application est :

```text
plafond serveur
  -> politique d'environnement
  -> politique tenant
  -> politique dépôt
  -> politique du rôle
  -> valeur demandée
```

À chaque niveau, la valeur la plus restrictive gagne. Une requête peut réduire une page, une durée ou un budget ; elle ne peut pas dépasser le plafond effectif. Une valeur absente reçoit le défaut du serveur. Une valeur négative, ambiguë ou supérieure au plafond est refusée, elle n'est pas silencieusement élargie.

Les limites de sécurité codées dans un profil immuable, notamment réseau, image, volumes et secrets, ne sont pas des paramètres numériques ajustables.

## 3. Transport MCP

| Limite | Défaut/plafond v1 | Comportement |
|---|---:|---|
| corps de requête | 1 048 576 octets | rejet avant parsing complet si possible |
| corps de réponse synchrone | 65 536 octets | gros résultats par pagination ou ressource |
| timeout d'un appel court | 20 s | deadline propagée ; `TIMEOUT` si dépassée |
| appels en vol par serveur | 16 | backpressure avant l'appel |
| appels en vol par tâche | 4 | empêche le fan-out d'une tâche |
| warnings par réponse | 32 | troncature explicitement signalée |
| références de preuve | 64 | manifeste dédié au-delà |

Une opération longue retourne rapidement un handle ; le timeout de 20 secondes ne représente pas la durée du job. La consultation du job reste bornée séparément.

## 4. Retry et idempotence

| Classe | Tentatives totales | Conditions |
|---|---:|---|
| lecture pure | 3 | seulement `DEPENDENCY_UNAVAILABLE` ou `TIMEOUT`, backoff exponentiel avec jitter |
| opération à effet | 2 | clé d'idempotence obligatoire et backend capable de retrouver l'effet existant |
| validation, auth, policy, limite, conflit | 1 | jamais de retry automatique |

Le compteur inclut la première tentative. Un retry ne dépasse jamais la deadline initiale. Une réponse `INDETERMINATE` n'est pas un incident transitoire à rejouer automatiquement : elle bloque le gate et conserve les preuves partielles.

Pour les effets SCM, un timeout après push déclenche d'abord une lecture de réconciliation ; il ne provoque pas aveuglément une nouvelle création.

## 5. Contexte dépôt

| Capacité | Défaut | Plafond |
|---|---:|---:|
| fichier éligible sur disque | — | 1 Mio |
| contenu retourné par fichier | 16 Kio | 64 Kio |
| longueur de chemin | — | 1 024 octets |
| profondeur `list_tree` | 6 | 12 |
| entrées `list_tree` par page | 200 | 1 000 |
| entrées `list_tree` cumulées | — | 5 000 |
| fichiers inspectés par recherche | — | 1 000 |
| résultats de recherche par page | 50 | 200 |
| résultats de recherche cumulés | — | 500 |
| longueur de requête | — | 256 octets |
| extrait par occurrence | — | 2 048 octets |
| symboles par page/cumulés | 100 | 500 / 2 000 |
| dépendances par page/cumulées | 500 | 2 000 / 5 000 |

`list_tree`, `search_code`, `get_symbols` et `get_dependencies` utilisent un curseur opaque. Le serveur compte le cumul associé au curseur ; ouvrir de nouvelles pages ne réinitialise pas artificiellement le plafond d'une requête logique.

Les fichiers de plus de 1 Mio, binaires ou exclus peuvent être cités comme non lisibles, mais leur contenu n'est pas injecté au modèle.

## 6. Sandbox

### 6.1 Admission et conservation

| Limite | Valeur v1 |
|---|---:|
| patch | 1 Mio |
| sortie totale conservée | 64 Kio |
| page de sortie | 16 Kio |
| jobs concurrents globaux | 2 |
| jobs en attente | 32 |
| jobs actifs par tâche | 2 |
| jobs cumulés par tâche | 12 |
| snapshots retenus | 500 |
| rétention après terminaison | 7 jours |
| heartbeat | 15 s |
| polling | 250 ms, pendant 20 min au plus |

Le contrôleur refuse une admission lorsqu'un quota est atteint ; il ne crée ni snapshot ni conteneur orphelin. Le rejeu d'une clé d'idempotence existante est servi avant de consommer un nouveau quota.

### 6.2 Ressources et profils

Chaque job est limité à 2 Gio, 2 CPU et 512 PIDs. Les timeouts sont :

- patch check/application : 3 minutes, réseau `none` ;
- tests : 15 minutes, seules dépendances autorisées ;
- qualité Sonar : 15 minutes, dépendances et SonarQube ;
- sécurité Syft/Trivy : 10 minutes, proxy allow-listé sur `sandbox-egress`; cible sans réseau avec bases préchargées.

Compose sépare actuellement `sandbox-egress` et `sandbox-quality`, tous deux internes, et ne raccorde aucun job au
réseau de contrôle `factory`. Cette isolation locale doit encore être transposée en NetworkPolicies dans le backend
GKE cible.

## 7. Boucle agentique et budget

| Limite | Valeur v1 |
|---|---:|
| tours par invocation d'agent | 8 |
| appels d'outil par tour | 12 |
| appels d'outil par invocation | 32 |
| appels d'outil par tâche | 96 |
| appels concurrents par tâche | 4 |
| appels identiques consécutifs | 2 |
| tokens d'entrée par tâche | 120 000 |
| tokens de sortie par tâche | 40 000 |
| coût total par tâche | 5,00 EUR |
| durée murale totale | 45 minutes |
| réparations de patch | 2 |

Le premier plafond atteint arrête proprement la boucle. L'agent doit alors produire un résultat final borné si cela reste possible ; sinon la tâche passe en échec explicite `LIMIT_EXCEEDED`. Aucun fallback vers un modèle plus coûteux ou un outil plus puissant n'est automatique.

Les budgets sont comptés par l'orchestrateur à partir des usages normalisés renvoyés par LiteLLM. En cas de télémétrie de tokens ou de coût absente/incohérente, la poursuite agentique est refusée après la tolérance technique définie par l'exploitation ; elle n'est pas considérée gratuite.

## 8. Livraison et preuves

| Objet | Limite v1 |
|---|---:|
| dépôts par tâche | 1 |
| draft PR par tentative | 1 |
| titre/message technique | 256 octets maximum |
| timeout de livraison | 2 minutes |
| âge maximal d'une approbation | 24 heures |
| artefact individuel | 10 Mio |
| artefacts par tentative | 64 |
| manifeste de preuves | 1 Mio |
| résumé transmis à un agent | 64 Kio |

Une limite de taille n'autorise pas à tronquer silencieusement une preuve requise. Une preuve trop grande doit utiliser le mécanisme d'artefact adapté ; une preuve incomplète produit `INDETERMINATE`.
Le plafond de politique de 10 Mio n'est pas la valeur du backend local : Compose configure actuellement Evidence
MCP à 1 Mio par artefact, et la valeur la plus restrictive s'applique.

## 9. Comportement en cas de dépassement

1. Refuser avec `LIMIT_EXCEEDED`, `retryable=false` et un `safe_message` sans donnée sensible.
2. Émettre métrique et événement d'audit avec serveur, outil, classe de limite et valeur effective ; ne pas utiliser `task_id` comme label de métrique.
3. Conserver les preuves partielles déjà produites avec le statut `PARTIAL`.
4. Annuler/nettoyer le travail devenu inutile dans les limites du timeout de cleanup.
5. Ne jamais réactiver le chemin `DIRECT` pour contourner une limite MCP.
6. Une augmentation nécessite une politique autorisée et versionnée ; un paramètre de ticket ou une sortie LLM ne suffit jamais.

## 10. État d'implémentation

| Domaine | Déjà présent | Reste à aligner |
|---|---|---|
| contexte | fichier 1 Mio, arbre/recherche bornés, réponses fichier 64 Kio | cumul pagination, deadlines interruptibles, symboles/dépendances |
| sandbox | concurrence, file, quotas tâche, sortie, patch, rétention, ressources et timeouts | plafond de 12 jobs par tâche et egress par destination |
| client MCP | timeout 20 s, polling 20 min | retry/circuit breaker/concurrence selon MCP-027 |
| agents | deux réparations de patch | tours, appels, tokens, coût et durée selon MCP-171/MCP-176 |
| SCM | timeouts directs partiels | PR/tentative, approbation 24 h et réconciliation idempotente |
| preuves | sortie sandbox bornée | taille/type/manifeste dans `evidence-mcp` |

## 11. Critères de clôture de MCP-011

- [x] Octets, résultats, durées, concurrence et rétention ont des valeurs v1.
- [x] Retry et idempotence sont définis par classe d'opération.
- [x] Tours, appels d'outils, tokens, coût et durée agentiques sont bornés.
- [x] La priorité des politiques et le comportement au dépassement sont explicites.
- [x] Une politique machine-readable versionnée accompagne le document.
