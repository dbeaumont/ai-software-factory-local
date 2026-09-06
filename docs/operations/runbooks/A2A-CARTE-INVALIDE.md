# Runbook — carte A2A invalide

## Détection

L'alerte `AiFactoryA2aCardInvalid` signale une carte expirée, une signature ou un `kid` invalide, une version
incompatible, une URL non autorisée ou des skills/scopes divergents du catalogue.

## Confinement immédiat

Suspendre les admissions du rôle concerné. Ne pas désactiver la vérification de signature, élargir l'allowlist ou
utiliser une carte mise en cache au-delà de la fenêtre stale autorisée.

## Diagnostic

Vérifier la carte publiée, le catalogue et les clés sans afficher de secret :

```bash
make a2a-config
make a2a-status
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 orchestrator
```

Comparer `version`, `url`, skills, scopes, dates de validité, issuer, audience, digest et `kid` à la configuration
versionnée. Distinguer expiration normale, rotation incomplète, dérive de catalogue et falsification.

## Rétablissement

Corriger ou republier la carte depuis un runtime qualifié. Pour une clé tournée, publier d'abord le JWK public,
attendre sa visibilité, signer la nouvelle carte, puis retirer l'ancienne clé après expiration des caches.

## Vérification et clôture

Exécuter `make a2a-config`, résoudre chaque carte deux fois (origine puis cache), vérifier son digest et attendre
deux fenêtres sans rejet. Clore avec la cause, la carte, le `kid` et le commit qualifié — jamais la clé privée.

## Escalade

Escalader immédiatement à Sécurité si la signature, l'issuer, l'URL ou les scopes ont été altérés. Utiliser
[Rollback A2A](ROLLBACK-A2A.md) si le producteur de cartes ne peut être réparé sans nouvelle version.
