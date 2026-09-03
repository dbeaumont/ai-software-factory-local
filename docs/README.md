# Guide de lecture de la documentation

Ce répertoire mélange documentation courante, décisions d'architecture, dossiers de qualification et rapports
historiques. Leur statut doit être pris en compte avant d'interpréter une affirmation au présent.

## Sources canoniques de l'état courant

| Besoin | Document |
|---|---|
| démarrer et utiliser le prototype | [`README.md`](../README.md) |
| comprendre l'état réellement câblé | [`RETRODOCUMENTATION.md`](RETRODOCUMENTATION.md) |
| connaître les écarts et prochaines actions | [`TODO.md`](TODO.md) |
| comprendre le déploiement logique des agents | [`AGENTS.md`](AGENTS.md) |
| vérifier la topologie locale | [`infrastructure/compose.yaml`](../infrastructure/compose.yaml) |

En cas de contradiction sur le comportement actif, le code et la configuration exécutable prévalent, puis la
rétrodocumentation. Une cible ou un gate passé ne prouve pas qu'une capacité est activée dans Compose.

## Catégories documentaires

| Répertoire | Nature | Règle de lecture |
|---|---|---|
| `adr/` | décisions acceptées | décrit un choix, qui peut être une cible non encore activée |
| `agents/` | catalogue et cycle de vie | normatif pour les rôles, sous réserve de leur qualification/activation |
| `mcp/` | conception, migration et qualification MCP | les rapports numérotés restent des preuves datées de leur lot |
| `mcp/baselines/` | résultats de campagnes | instantanés historiques ; ne pas les mettre à jour avec l'état courant |
| `multiagents/` | politiques, gates et qualification de l'architecture 04 | un verdict `PASS` valide le périmètre testé, pas la généralisation runtime |
| `runbooks/` | procédures d'exploitation | certaines ne s'appliquent qu'aux modes hiérarchiques non activés par défaut |
| `version-1.2.0-archi-04/` | cible, plan et état de la version 1.2 | distinguer code livré et chemin opérationnel actif |
| `version-1.0.0*`, `version-1.1.0*` | archives versionnées | snapshots conservés sans mise à niveau vers la version courante |
| `old/` | études remplacées | contexte historique uniquement, jamais une consigne de déploiement |

## Vocabulaire de maturité

- **Actif** : utilisé par `POST /api/tasks` avec la configuration Compose par défaut.
- **Disponible** : code, contrat ou service présent, mais désactivé ou non relié de bout en bout.
- **Cible** : architecture ou port préparatoire qui exige encore un adaptateur ou une qualification.
- **Qualifié** : périmètre précis ayant satisfait un gate daté ; ce terme ne signifie pas automatiquement actif.
- **Historique** : constat exact à la date du rapport, potentiellement remplacé depuis.

## Entretien

Toute modification d'architecture doit mettre à jour, dans cet ordre :

1. le code, les contrats ou les politiques qui font autorité ;
2. l'ADR ou le gate concerné ;
3. `RETRODOCUMENTATION.md` et `README.md` si l'état actif change ;
4. `TODO.md` pour fermer ou créer l'écart correspondant.

Les liens internes restent relatifs au dépôt. Les chemins absolus vers un poste de travail sont interdits.
