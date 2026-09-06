# A2A-183 — Déploiement de la flotte d'agents

Date d'exécution : 2026-09-07

Opérateur : Codex, sous autorisation de David

Commit source déployé : `84a651abcf125c0ce8062a465ffacaa34580a13c`

## Résultat

- Les 14 rôles A2A sont déployés avec le même runtime immuable
  `ai-factory-a2a-agent-runtime:0.2.0-a2a-cutover.2`.
- Le stockage PostgreSQL A2A et le fournisseur d'identité local sont `healthy`.
- Chaque runtime utilise sa PKI de rôle, une carte signée distincte, un worker Temporal versionné, ses réseaux MCP
  autorisés, le proxy LLM privé et le relais de notifications durable.
- Les admissions restent fermées et l'orchestrateur reste arrêté jusqu'au ticket A2A-184.

## Images et version Temporal

- Runtime A2A :
  `sha256:f80d0237b83d31719c0add7ce68e250eaaff67226c5f44e7cf9e3227dfecffe4`.
- Label source du runtime : `84a651abcf125c0ce8062a465ffacaa34580a13c` ; date de build
  `2026-09-06T23:38:21Z`.
- Identité locale :
  `sha256:5768101777797179ec15a84bbcebf871037ae67050e06052e7249ef8fbc80ca5`.
- Déploiement Temporal : `ai-factory-a2a-agents`.
- Build ID Temporal : `a2a-agent-84a651a`, créé le `2026-09-06T23:40:01.671644003Z`.
- `temporal worker deployment describe-version` retourne les 28 associations attendues : une file workflow et
  une file activité pour chacun des 14 rôles, sans backlog observé lors du contrôle ciblé.

## Contrôles exécutés

- `make a2a-config` : succès ; 14 rôles explicites, réseau A2A privé, identité et cloisonnement MCP vérifiés.
- `make a2a-up-full` : succès ; les 14 readiness strictes sont passées au vert.
- `make a2a-smoke` : succès ; les 14 cartes sont signées et cohérentes avec leur rôle.
- Smoke d'identité intégré : succès ; le flux `client_credentials` émet un JWT de 300 secondes, puis le
  Supervisor valide mTLS, signature, issuer, audience et scopes sur un appel `tasks/get` sans effet de bord.
- `mvn -B -s apps/orchestrator/.mvn/settings-direct.xml -pl apps/a2a-agent-runtime -am test` : succès,
  76 tests runtime et 10 tests `agent-core`, zéro échec et zéro test ignoré.
- Validation syntaxique Bash, Ruby et Python : succès.

## Observations de sûreté

- Aucun port hôte n'est publié par l'identité, la base ou les agents.
- Le réseau `a2a-internal` est déclaré `internal`; le réseau `llm-internal` isole l'accès des agents au proxy LLM.
- Les secrets OAuth2, clés privées et jetons n'ont pas été écrits dans cette preuve ni dans les journaux du smoke.
- Le relais de notification est démarré ; son acquittement applicatif sera vérifié après activation de
  l'orchestrateur dans A2A-184 et pendant le parcours de production A2A-186.
