# Runbook — certificat A2A expiré ou proche de l'expiration

## Détection

La readiness refuse un certificat dont la validité restante est inférieure à
`AI_FACTORY_A2A_TLS_MINIMUM_VALIDITY_SECONDS`. Les logs TLS distinguent expiration, chaîne inconnue, révocation et
nom de service invalide.

## Confinement immédiat

Suspendre les admissions du rôle. Ne désactiver ni mTLS ni la vérification de nom, ne réutiliser aucune clé privée
expirée et ne copier aucun secret dans les logs ou le ticket.

## Diagnostic

```bash
make a2a-pki
make a2a-status
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full logs --tail=200 a2a-developer
```

Contrôler dates, SAN, issuer, serial, CRL, permissions du fichier et concordance certificat/clé. Vérifier côté
client et serveur afin d'identifier la moitié de la rotation restée sur l'ancienne CA.

## Rétablissement

Exécuter `make a2a-pki-rotate`, conserver la sauvegarde récupérable produite, déployer le bundle de confiance
avant le certificat actif puis redémarrer progressivement les runtimes. Révoquer l'ancien certificat seulement
après validation des nouvelles connexions.

## Vérification et clôture

Relancer `make a2a-pki`, vérifier une poignée de main mutuellement authentifiée pour chaque rôle, readiness verte
et absence d'erreur TLS pendant deux fenêtres. Tracer serials et échéances, jamais les clés.

## Escalade

Escalader à Sécurité pour révocation, clé exposée, issuer inattendu ou rotation échouée.
