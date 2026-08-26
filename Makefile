SHELL := /bin/bash

# Load local configuration for Make targets. Docker Compose reads .env as well.
-include .vault
-include .env

# Couleurs pour l'affichage
GREEN  := \033[0;32m
YELLOW := \033[1;33m
BLUE   := \033[0;34m
CYAN   := \033[0;36m
RED    := \033[0;31m
NC     := \033[0m

.PHONY: help init build up all model bootstrap tokens demo test package config status restart logs urls down clean

help:
	@echo -e "$(YELLOW)AI Software Factory prototype - Commandes :$(NC)"
	@echo -e "  $(CYAN)make init$(NC)       - create .env and .vault from their examples"
	@echo -e "  $(CYAN)make build$(NC)      - build orchestrator + sandbox images"
	@echo -e "  $(CYAN)make up$(NC)         - start the complete local factory stack"
	@echo -e "  $(CYAN)make all$(NC)        - reset data and start a fully bootstrapped local factory"
	@echo -e "  $(CYAN)make model$(NC)      - pull configured Ollama model"
	@echo -e "  $(CYAN)make bootstrap$(NC)  - initialize demo Gitea repository and service tokens"
	@echo -e "  $(CYAN)make tokens$(NC)     - validate or regenerate local Gitea and SonarQube tokens"
	@echo -e "  $(CYAN)make demo$(NC)       - submit an AI task against the demo repository"
	@echo -e "  $(CYAN)make test$(NC)       - run orchestrator tests"
	@echo -e "  $(CYAN)make package$(NC)    - package orchestrator without tests"
	@echo -e "  $(CYAN)make config$(NC)     - validate and render Docker Compose configuration"
	@echo -e "  $(CYAN)make status$(NC)     - show containers"
	@echo -e "  $(CYAN)make restart$(NC)    - restart the orchestrator"
	@echo -e "  $(CYAN)make logs$(NC)       - follow orchestrator logs"
	@echo -e "  $(CYAN)make urls$(NC)       - list available service and API URLs"
	@echo -e "  $(CYAN)make down$(NC)       - stop stack"
	@echo -e "  $(CYAN)make clean$(NC)      - stop and remove volumes (destructive)"

init:
	@test -f .env || cp .env.example .env
	@test -f .vault || cp .vault.example .vault
	@echo -e "$(GREEN).env and .vault ready$(NC)"

build:
	@echo -e "$(BLUE)Building sandbox and orchestrator images...$(NC)"
	@test -n "$(SYFT_VERSION)" || (echo "SYFT_VERSION must be defined in .env" >&2; exit 1)
	@test -n "$(TRIVY_VERSION)" || (echo "TRIVY_VERSION must be defined in .env" >&2; exit 1)
	@test -n "$(TRIVY_PRELOAD_DB)" || (echo "TRIVY_PRELOAD_DB must be defined in .env" >&2; exit 1)
	docker build \
		--build-arg TRIVY_VERSION="$(TRIVY_VERSION)" \
		--build-arg SYFT_VERSION="$(SYFT_VERSION)" \
		--build-arg TRIVY_PRELOAD_DB="$(TRIVY_PRELOAD_DB)" \
		-t ai-factory-sandbox:local ./sandbox
	docker compose build orchestrator factory-web
	@echo -e "$(GREEN)Build complete!$(NC)"

up: init build
	@echo -e "$(BLUE)Starting local factory stack...$(NC)"
	docker compose up -d
	@echo -e "$(GREEN)Stack started!$(NC)"
	@$(MAKE) urls

all:
	@echo -e "$(YELLOW)Resetting and bootstrapping complete factory...$(NC)"
	$(MAKE) clean
	$(MAKE) up
	$(MAKE) model
	$(MAKE) bootstrap
	@echo -e "$(GREEN)Full factory ready!$(NC)"

model:
	@echo -e "$(BLUE)Pulling Ollama model $(OLLAMA_MODEL)...$(NC)"
	docker compose exec ollama ollama pull $(OLLAMA_MODEL)
	@echo -e "$(GREEN)Model $(OLLAMA_MODEL) pulled!$(NC)"

bootstrap: init
	@echo -e "$(BLUE)Bootstrapping Gitea and SonarQube...$(NC)"
	./scripts/bootstrap-gitea.sh
	./scripts/bootstrap-sonar.sh
	docker compose up -d --force-recreate orchestrator
	@echo -e "$(GREEN)Bootstrap complete!$(NC)"

tokens: init
	@echo -e "$(BLUE)Updating Gitea and SonarQube tokens...$(NC)"
	./scripts/bootstrap-gitea.sh --token-only
	./scripts/bootstrap-sonar.sh
	docker compose up -d --force-recreate orchestrator
	@echo -e "$(GREEN)Tokens updated!$(NC)"

demo:
	@echo -e "$(BLUE)Submitting demo task...$(NC)"
	./scripts/demo.sh

test:
	@echo -e "$(BLUE)Running orchestrator tests...$(NC)"
	if [ -x ./mvnw ]; then ./mvnw -f orchestrator/pom.xml test; else mvn -f orchestrator/pom.xml test; fi

package:
	@echo -e "$(BLUE)Packaging orchestrator...$(NC)"
	if [ -x ./mvnw ]; then ./mvnw -f orchestrator/pom.xml package -DskipTests; else mvn -f orchestrator/pom.xml package -DskipTests; fi

config:
	@echo -e "$(BLUE)Validating Docker Compose configuration...$(NC)"
	docker compose config >/dev/null
	@echo -e "$(GREEN)Configuration is valid!$(NC)"

restart:
	@echo -e "$(YELLOW)Restarting orchestrator...$(NC)"
	docker compose restart orchestrator
	@echo -e "$(GREEN)Orchestrator restarted!$(NC)"

status:
	docker compose ps

down:
	@echo -e "$(YELLOW)Stopping local factory stack...$(NC)"
	docker compose down
	@echo -e "$(GREEN)Stack stopped!$(NC)"

logs:
	docker compose logs -f orchestrator

urls:
	@echo ""
	@echo -e "$(YELLOW)Core Services:$(NC)"
	@echo -e "  - Factory web:  $(GREEN)http://localhost:$(WEB_APP_PORT)$(NC)"
	@echo -e "  - Gitea:        $(GREEN)http://localhost:$(GITEA_HTTP_PORT)$(NC) (user: $(GITEA_REVIEWER_USER), password: $(GITEA_REVIEWER_PASSWORD))"
	@echo -e "  - Gitea API:    $(GREEN)http://localhost:$(GITEA_HTTP_PORT)/api/v1$(NC)"
	@echo -e "  - Gitea SSH:    $(GREEN)ssh://git@localhost:$(GITEA_SSH_PORT)$(NC)"
	@echo -e "  - Demo repo:    $(GREEN)http://localhost:$(GITEA_HTTP_PORT)/$(GITEA_ADMIN_USER)/customer-api$(NC)"
	@echo -e "  - Ollama API:   $(GREEN)http://localhost:$(OLLAMA_PORT)$(NC)"
	@echo -e "  - Orchestrator: $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)$(NC)"
	@echo -e "  - Tasks API:    $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/api/tasks$(NC)"
	@echo -e "  - Create task:  POST $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/api/tasks$(NC)"
	@echo -e "  - Task detail:  GET  $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/api/tasks/<TASK_ID>$(NC)"
	@echo -e "  - Approve task: POST $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/api/tasks/<TASK_ID>/approve$(NC)"
	@echo -e "  - Actuator:     $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/actuator$(NC)"
	@echo -e "  - Health:       $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/actuator/health$(NC)"
	@echo -e "  - Metrics:      $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/actuator/prometheus$(NC)"
	@echo ""
	@echo -e "$(YELLOW)Quality, Artifacts & Observability:$(NC)"
	@echo -e "  - SonarQube:    $(GREEN)http://localhost:$(SONAR_PORT)$(NC) (user: $(SONAR_ADMIN_LOGIN), password: $(SONAR_ADMIN_PASSWORD))"
	@echo -e "  - Artifactory:  $(GREEN)http://localhost:$(ARTIFACTORY_PORT)$(NC) (user: admin, password: password)"
	@echo -e "  - Prometheus:   $(GREEN)http://localhost:$(PROMETHEUS_PORT)$(NC)"
	@echo -e "  - Grafana:      $(GREEN)http://localhost:$(GRAFANA_PORT)$(NC) (user: $(GRAFANA_ADMIN_USER), initial password: $(GRAFANA_ADMIN_PASSWORD))"
	@echo ""

clean:
	@echo -e "$(RED)Cleaning stack and removing volumes...$(NC)"
	docker compose down -v --remove-orphans
	@echo -e "$(GREEN)Clean complete!$(NC)"
