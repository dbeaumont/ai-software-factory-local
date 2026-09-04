# Documentation de l’AI Software Factory

Cette documentation est organisée par usage et par statut. Le code et la configuration exécutable restent la source de vérité pour le comportement actif.

## Commencer ici

| Besoin | Document |
|---|---|
| démarrer et utiliser le prototype | [`README.md`](../README.md) |
| comprendre l’état réellement câblé | [`overview/current-state.md`](overview/current-state.md) |
| obtenir une synthèse pour la décision | [`overview/executive-summary.md`](overview/executive-summary.md) |
| suivre les écarts et prochaines actions | [`delivery/roadmap/README.md`](delivery/roadmap/README.md) |
| intervenir sur un incident | [`operations/runbooks/README.md`](operations/runbooks/README.md) |

## Répertoires

| Répertoire | Contenu | Statut de lecture |
|---|---|---|
| [`overview/`](overview/README.md) | synthèses, état courant et vue d’architecture | documents d’orientation courants |
| [`architecture/`](architecture/README.md) | ADR, agents et conception MCP | décisions et conception de référence |
| [`operations/`](operations/README.md) | maintenance et runbooks | procédures d’exploitation |
| [`delivery/`](delivery/README.md) | roadmap et migrations | trajectoire de livraison |
| [`qualification/`](qualification/README.md) | gates, politiques de qualification et rapports | validité limitée au périmètre testé |
| [`evidence/`](evidence/README.md) | jeux de preuves et résultats horodatés | artefacts immuables, non réécrits |
| [`archive/`](archive/README.md) | versions livrées et études remplacées | contexte historique, jamais une consigne active |
| [`assets/`](assets/) | diagrammes et médias utilisés par les documents | ressources partagées |

## Vocabulaire de maturité

- **Actif** : utilisé par `POST /api/tasks` avec la configuration Compose par défaut.
- **Disponible** : code, contrat ou service présent, mais désactivé ou non relié de bout en bout.
- **Cible** : architecture ou port préparatoire qui exige encore un adaptateur ou une qualification.
- **Qualifié** : périmètre précis ayant satisfait un gate daté ; cela ne signifie pas automatiquement actif.
- **Historique** : constat exact à la date du document, potentiellement remplacé depuis.

## Entretien

Toute modification d'architecture doit mettre à jour, dans cet ordre :

1. le code, les contrats ou les politiques qui font autorité ;
2. l'ADR ou le gate concerné ;
3. l’état courant et les synthèses lorsque le comportement actif change ;
4. la roadmap pour fermer ou créer l’écart correspondant.

Les liens internes restent relatifs au dépôt. Les chemins absolus vers un poste de travail sont interdits.
