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
COMPOSE := docker compose --env-file .env -f infrastructure/compose.yaml

define log-target
	@echo -e "$(CYAN)[target: $@]$(NC)"
endef

.PHONY: help init build up all bootstrap tokens demo test test-sandbox-runtime mcp-shadow-campaign mcp-shadow-report package config status restart logs urls down clean

help:
	$(log-target)
	@echo -e "$(YELLOW)AI Software Factory prototype - Commandes :$(NC)"
	@echo -e "  $(CYAN)make init$(NC)       - create .env and .vault from their examples"
	@echo -e "  $(CYAN)make build$(NC)      - build orchestrator + sandbox images"
	@echo -e "  $(CYAN)make up$(NC)         - start the complete local factory stack"
	@echo -e "  $(CYAN)make all$(NC)        - reset data and start a fully bootstrapped local factory"
	@echo -e "  $(CYAN)make bootstrap$(NC)  - initialize demo Gitea repository and service tokens"
	@echo -e "  $(CYAN)make tokens$(NC)     - validate or regenerate local Gitea and SonarQube tokens"
	@echo -e "  $(CYAN)make demo$(NC)       - submit an AI task against the demo repository"
	@echo -e "  $(CYAN)make test$(NC)       - run orchestrator and MCP server tests"
	@echo -e "  $(CYAN)make test-sandbox-runtime$(NC) - verify effective Docker sandbox constraints"
	@echo -e "  $(CYAN)make mcp-shadow-campaign$(NC) - validate the 20-task campaign (set CAMPAIGN_ARGS=--execute to run)"
	@echo -e "  $(CYAN)make mcp-shadow-report$(NC) - generate the MCP shadow campaign report"
	@echo -e "  $(CYAN)make package$(NC)    - package orchestrator without tests"
	@echo -e "  $(CYAN)make config$(NC)     - validate and render Docker Compose configuration"
	@echo -e "  $(CYAN)make status$(NC)     - show containers"
	@echo -e "  $(CYAN)make restart$(NC)    - restart the orchestrator"
	@echo -e "  $(CYAN)make logs$(NC)       - follow orchestrator logs"
	@echo -e "  $(CYAN)make urls$(NC)       - list available service and API URLs"
	@echo -e "  $(CYAN)make down$(NC)       - stop stack"
	@echo -e "  $(CYAN)make clean$(NC)      - stop and remove volumes (destructive)"

init:
	$(log-target)
	@test -f .env || cp .env.example .env
	@test -f .vault || cp .vault.example .vault
	@echo -e "$(GREEN).env and .vault ready$(NC)"

build:
	$(log-target)
	@echo -e "$(BLUE)Building sandbox and orchestrator images...$(NC)"
	@test -n "$(SYFT_VERSION)" || (echo "SYFT_VERSION must be defined in .env" >&2; exit 1)
	@test -n "$(TRIVY_VERSION)" || (echo "TRIVY_VERSION must be defined in .env" >&2; exit 1)
	@test -n "$(TRIVY_PRELOAD_DB)" || (echo "TRIVY_PRELOAD_DB must be defined in .env" >&2; exit 1)
	docker build \
		--build-arg TRIVY_VERSION="$(TRIVY_VERSION)" \
		--build-arg SYFT_VERSION="$(SYFT_VERSION)" \
		--build-arg TRIVY_PRELOAD_DB="$(TRIVY_PRELOAD_DB)" \
		-t ai-factory-sandbox:local ./infrastructure/sandbox
	./scripts/pin-sandbox-image.sh .env ai-factory-sandbox:local
	$(COMPOSE) build repository-context-mcp sandbox-execution-mcp orchestrator factory-web
	@echo -e "$(GREEN)Build complete!$(NC)"

up: init build
	$(log-target)
	@echo -e "$(BLUE)Starting local factory stack...$(NC)"
	$(COMPOSE) up -d
	@echo -e "$(GREEN)Stack started!$(NC)"
	@$(MAKE) urls

all:
	$(log-target)
	@echo -e "$(YELLOW)Resetting and bootstrapping complete factory...$(NC)"
	$(MAKE) clean
	$(MAKE) up
	$(MAKE) bootstrap
	@echo -e "$(GREEN)Full factory ready!$(NC)"

bootstrap: init
	$(log-target)
	@echo -e "$(BLUE)Bootstrapping Gitea and SonarQube...$(NC)"
	./scripts/bootstrap-gitea.sh
	./scripts/bootstrap-sonar.sh
	$(COMPOSE) up -d --force-recreate orchestrator
	@echo -e "$(GREEN)Bootstrap complete!$(NC)"

tokens: init
	$(log-target)
	@echo -e "$(BLUE)Updating Gitea and SonarQube tokens...$(NC)"
	./scripts/bootstrap-gitea.sh --token-only
	./scripts/bootstrap-sonar.sh
	$(COMPOSE) up -d --force-recreate orchestrator
	@echo -e "$(GREEN)Tokens updated!$(NC)"

demo:
	$(log-target)
	@echo -e "$(BLUE)Submitting demo task...$(NC)"
	./scripts/demo.sh

test:
	$(log-target)
	@echo -e "$(BLUE)Running orchestrator and MCP server tests...$(NC)"
	if [ -x ./apps/orchestrator/mvnw ]; then ./apps/orchestrator/mvnw -f apps/orchestrator/pom.xml test; else mvn -f apps/orchestrator/pom.xml test; fi
	mvn -f apps/mcp/repository-context-server/pom.xml test
	mvn -f apps/mcp/sandbox-execution-server/pom.xml test

test-sandbox-runtime:
	$(log-target)
	@echo -e "$(BLUE)Verifying effective Docker sandbox constraints...$(NC)"
	AI_FACTORY_RUN_DOCKER_INTEGRATION_TESTS=true mvn -f apps/mcp/sandbox-execution-server/pom.xml -Dtest=DockerSandboxRuntimeIntegrationTest test
	@echo -e "$(GREEN)Docker sandbox constraints verified!$(NC)"

mcp-shadow-campaign:
	$(log-target)
	./scripts/mcp-context-shadow-campaign.sh $(CAMPAIGN_ARGS)

mcp-shadow-report:
	$(log-target)
	ORCHESTRATOR_PORT="$(ORCHESTRATOR_PORT)" ./scripts/mcp-shadow-report.sh

package:
	$(log-target)
	@echo -e "$(BLUE)Packaging orchestrator...$(NC)"
	if [ -x ./apps/orchestrator/mvnw ]; then ./apps/orchestrator/mvnw -f apps/orchestrator/pom.xml package -DskipTests; else mvn -f apps/orchestrator/pom.xml package -DskipTests; fi

config:
	$(log-target)
	@echo -e "$(BLUE)Validating Docker Compose configuration...$(NC)"
	$(COMPOSE) config >/dev/null
	@echo -e "$(GREEN)Configuration is valid!$(NC)"

restart:
	$(log-target)
	@echo -e "$(YELLOW)Restarting orchestrator...$(NC)"
	$(COMPOSE) restart orchestrator
	@echo -e "$(GREEN)Orchestrator restarted!$(NC)"

status:
	$(log-target)
	$(COMPOSE) ps

down:
	$(log-target)
	@echo -e "$(YELLOW)Stopping local factory stack...$(NC)"
	$(COMPOSE) down
	@echo -e "$(GREEN)Stack stopped!$(NC)"

logs:
	$(log-target)
	$(COMPOSE) logs -f orchestrator

urls:
	$(log-target)
	@echo ""
	@echo -e "$(YELLOW)Core Services:$(NC)"
	@echo -e "  - Factory web:  $(GREEN)http://localhost:$(WEB_APP_PORT)$(NC)"
	@echo -e "  - Gitea:        $(GREEN)http://localhost:$(GITEA_HTTP_PORT)$(NC) (user: $(GITEA_REVIEWER_USER), password: $(GITEA_REVIEWER_PASSWORD))"
	@echo -e "  - Gitea API:    $(GREEN)http://localhost:$(GITEA_HTTP_PORT)/api/v1$(NC)"
	@echo -e "  - Gitea SSH:    $(GREEN)ssh://git@localhost:$(GITEA_SSH_PORT)$(NC)"
	@echo -e "  - Maven demo:   $(GREEN)http://localhost:$(GITEA_HTTP_PORT)/$(GITEA_ADMIN_USER)/customer-api$(NC)"
	@echo -e "  - Gradle demo:  $(GREEN)http://localhost:$(GITEA_HTTP_PORT)/$(GITEA_ADMIN_USER)/inventory-gradle$(NC)"
	@echo -e "  - Node demo:    $(GREEN)http://localhost:$(GITEA_HTTP_PORT)/$(GITEA_ADMIN_USER)/checkout-node$(NC)"
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
	$(log-target)
	@echo -e "$(RED)Cleaning stack and removing volumes...$(NC)"
	$(COMPOSE) down -v --remove-orphans
	@echo -e "$(GREEN)Clean complete!$(NC)"
