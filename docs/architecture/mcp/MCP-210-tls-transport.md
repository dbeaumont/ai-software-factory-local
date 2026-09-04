# MCP-210 — TLS des transports MCP

Les clients acceptent les URI HTTPS nativement. Au démarrage, l'orchestrateur refuse désormais toute URI MCP en HTTP hors profils `local`, `dev`, `test` et hors adresse loopback.

En local Compose, le trafic HTTP interne reste explicitement limité au profil `local`. En cible Cloud Run, les variables `AI_FACTORY_MCP_*_URL` doivent contenir les URL HTTPS privées du service ; le démarrage échoue sinon. Les identités applicatives du lot suivant complètent le TLS de plateforme.
