# A2A-181 — Gel des admissions et drain

- Date UTC : `2026-09-06T22:54:20Z`
- Commit contrôlé : `1d63aa97512ef453cd500c8b3db710eff84c95ac`
- Environnement : Docker Compose local macOS
- Décision : `PASS`

## Contrôles exécutés

1. `make admissions-close` a fermé le commutateur durable avant tout redéploiement.
2. `make admissions-status` a confirmé :
   - `admissions_open = false` ;
   - `reason = temporal_cutover` ;
   - `revision = 6` ;
   - `updated_at = 2026-09-06 22:50:43.135457+00`.
3. La requête Temporal `ExecutionStatus="Running"` sur le namespace
   `ai-factory-local` a retourné une liste vide (`[]`). Il n'existe donc plus
   d'exécution Temporal incompatible à laisser terminer ou à annuler.
4. Les projections métier non terminales restantes ont une dernière mise à
   jour antérieure au gel et aucune exécution Temporal active associée. Elles
   sont conservées sans réécriture dans la sauvegarde de cutover afin de ne pas
   fabriquer d'historique.

## Correctif opératoire

La valeur d'exemple `AI_FACTORY_A2A_CARD_PROVIDER_NAME` contenant des espaces
est désormais entre guillemets. `.env.example` peut ainsi être sourcé par les
scripts Make sans interpréter `Software` comme une commande.

## Conclusion

Les admissions restent fermées. Le prérequis de sauvegarde A2A-182 peut être
exécuté ; aucune réouverture n'est autorisée avant A2A-187.
