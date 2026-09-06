# TEMP-110 — Ouverture générale des admissions

> Résultat : `PASS`
>
> Ouverture UTC : `2026-09-06T07:29:08.836491Z`
>
> Source applicative approuvée : `a589f5c4c93088650bc7f46a13655cf10b92bf94`

## Préconditions contrôlées

- gate TEMP-103R approuvé sur les images finales exactes ;
- TEMP-106, TEMP-108 et TEMP-109 terminés avec succès ;
- gel `temporal-cutover-v1` toujours valide ;
- readiness orchestrateur `UP` ;
- image active
  `sha256:59717e13f82355352f73a8eca8e9c17219f9c8079b94c3ec693f398f08b7d374` ;
- smoke test terminé et nettoyé sans workflow ouvert résiduel.

## Bascule

La commande opérateur `make admissions-open` a modifié l'unique ligne de contrôle globale :

| Champ | Valeur |
|---|---|
| `admissions_open` | `true` |
| `reason` | `normal_operation` |
| `revision` | `5` |
| `updated_at` | `2026-09-06T07:29:08.836491Z` |

L'endpoint `/api/capabilities` publie les mêmes valeurs. L'interface utilise ce booléen pour masquer la bannière de
maintenance et réactiver la soumission.

Le contrôle est global : il ne prend ni dépôt, ni catégorie, ni utilisateur, ni pourcentage en paramètre. Temporal
est l'unique implémentation de production de `WorkflowCoordinator`; aucune double exécution ni route de fallback
locale n'est possible.
