# TEMP-103R — Candidat final de bascule

> Résultat technique : `PASS`
>
> Décision : `PENDING`
>
> Source applicative : `a589f5c4c93088650bc7f46a13655cf10b92bf94`
>
> Orchestrateur : `sha256:59717e13f82355352f73a8eca8e9c17219f9c8079b94c3ec693f398f08b7d374`
>
> Interface : `sha256:59beb07a9c24abb3882465d4bc5f5977b398418714ab29ad74fd67c8f2a8521c`

## Pourquoi une réautorisation

La qualification TEMP-102 et l'autorisation TEMP-103 portent sur l'image exacte
`sha256:ef7b416ef628b3629e21d597b62be85aedf480594f530d814635baf4442d604e`. La fermeture durable des admissions
et la suppression définitive du coordinateur local, réalisées conformément au plan, modifient l'image finale.
L'instance active n'a pas été remplacée : TEMP-106 attend une nouvelle décision sur les artefacts ci-dessus.

## Delta depuis la release approuvée

- ajout du verrou global d'admission PostgreSQL et de son gate fail-closed ;
- exposition de l'état de maintenance dans les capacités API et l'interface ;
- ajout des commandes opérateur de fermeture/ouverture ;
- ajout et validation des sauvegardes/restaurations de toutes les autorités ;
- suppression de `DeterministicWorkflowCoordinator`, de son pool local et de son traceur dédié ;
- adaptation des tests pour exercer directement les étapes extraites et exiger Temporal comme unique coordinateur.

## Preuves techniques du candidat

| Contrôle | Résultat |
|---|---|
| Diff du périmètre gelé contre `9698fa30aa16a43af5b2d4f56b82f1953eb95080` | vide |
| `make temporal-cutover-freeze` | `PASS` |
| Suite Java complète | `557` tests, `0` échec, `0` erreur, `1` ignoré |
| Barrière de déterminisme durant le build Docker | `8` tests, `0` échec, `0` erreur |
| Construction de l'image finale | `PASS`, Linux arm64, 229 369 839 octets |
| TEMP-104 fermeture des admissions | `PASS`, verrou fermé révision `2` |
| TEMP-105 sauvegarde et restauration isolée | `PASS`, tous les SHA-256 et volumes contrôlés |
| TEMP-107 suppression du chemin local | `PASS`, une seule implémentation de production |
| Image active avant décision | ancienne image `sha256:85ba020303afbab6aad1f850df055c735adb063d4b35d237cd37a652d534347e` |

Le candidat est conservé localement sous les tags
`ai-software-factory-orchestrator:temporal-cutover-a589f5c` et
`ai-software-factory-factory-web:temporal-cutover-a589f5c`. Les admissions restent fermées avec le motif
`temporal_cutover` jusqu'à la décision et aux contrôles TEMP-106 à TEMP-110.
