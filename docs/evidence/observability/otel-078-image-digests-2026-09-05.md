# OTEL-078 — épinglage des images

Toutes les images externes du runtime Compose et toutes les bases Dockerfile sont verrouillées par tag de version
et digest SHA-256. Les images construites par le dépôt gardent leur nom local ; le runner sandbox est lui-même
référencé par son identifiant de contenu `sha256:` dans `.env`.

Le 5 septembre 2026 :

- `docker compose config --quiet` passe ;
- `scripts/check-pinned-images.sh` ne trouve aucune image externe flottante ;
- la reconstruction des neuf images locales passe avec les bases épinglées ;
- LiteLLM utilise un argument de build fixé dans Compose, indépendamment d'un ancien `.env` local ;
- les images Collector/SigNoz/PostgreSQL/ClickHouse restent celles qualifiées sur Apple Silicon.

Les digests sont des références de supply chain, pas une preuve de signature ou de provenance. Leur mise à jour doit
repasser les tests d'architecture, de build et de démarrage sur `arm64` et `amd64`.
