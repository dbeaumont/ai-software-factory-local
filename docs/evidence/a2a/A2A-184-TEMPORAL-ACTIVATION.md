# A2A-184 — Activation de la version Temporal A2A

Date d'exécution : 2026-09-07

Opérateur : Codex, sous autorisation de David

Commit applicatif déployé : `b83961cf622b5c4901535f545151ef1ac199026f`

## Résultat

- L'image `ai-factory-orchestrator:latest` a été construite avec succès après exécution de la barrière
  embarquée de 115 tests, sans échec ni test ignoré.
- Digest local de l'image :
  `sha256:611e4bd2a50de1c6f27644c684212a293ee5c059b571b71ffa9497c9fe92368b`.
- La version `ai-factory-orchestrator:a2a-cutover-b83961c` a été enregistrée puis rendue courante par le job
  `temporal-worker-activation`, terminé avec le code 0 le `2026-09-06T23:57:34Z`.
- Aucun ramping n'est configuré : la version A2A est directement la version courante.
- Les sept files de coordination attendues sont enregistrées : `ai-factory-workflows`, `ai-factory-context`,
  `ai-factory-llm`, `ai-factory-sandbox`, `ai-factory-assurance`, `ai-factory-evidence` et `ai-factory-scm`.
- La readiness de l'orchestrateur est `UP` : moteur Temporal disponible, sept workers sur sept et flotte A2A
  complète prête.
- Pour chacun des 14 rôles, la carte est `READY` et Temporal remonte un poller workflow et un poller activité.

## Corrections validées pendant l'activation

- La barrière `A2aCutoverConfigurationTest` résout désormais les sources de façon portable dans le dépôt et dans
  le contexte de build Docker ; le contrôle global des sélecteurs de transport demeure dans
  `verify-a2a-compose-runtime.rb`.
- La politique d'URI autorise les résolutions privées uniquement pour les registres internes fermés `compose` et
  `gke`. L'allow-list HTTPS exacte, le pinning DNS et les refus loopback, link-local, multicast et métadonnées
  restent actifs. Une modification de l'adresse après pinning est rejetée par test.
- La readiness journalise seulement le rôle, le type et le message sanitizé d'un échec de carte, sans corps de
  carte, jeton ni secret.

## État des admissions

Le détail `a2aFleet.admissions=OPEN` signifie que la flotte est techniquement admissible. Le commutateur métier
global demeure volontairement fermé : `admissions_open=f`, raison `temporal_cutover`, révision 6. Il ne sera
rouvert qu'après le smoke de production A2A-186.
