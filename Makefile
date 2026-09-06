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
MAVEN_HOST_SETTINGS := $(if $(strip $(MAVEN_MIRROR_URL)),-s apps/orchestrator/.mvn/settings.xml,-s apps/orchestrator/.mvn/settings-direct.xml)

define log-target
	@echo -e "$(CYAN)[target: $@]$(NC)"
endef

.PHONY: help init a2a-pki a2a-pki-rotate a2a-secrets a2a-supply-chain a2a-config a2a-status a2a-cards a2a-smoke a2a-logs a2a-reset-state a2a-up-role a2a-up-full test-a2a-compose-integration test-a2a-compose-failures test-a2a-security test-a2a-performance build up all bootstrap bootstrap-signoz tokens demo test temporal-replay temporal-cutover-baseline temporal-cutover-freeze qualify-temporal-cutover admissions-status admissions-close admissions-open backup-temporal-cutover restore-temporal-cutover monitor-temporal-cutover test-temporal-compose test-temporal-ticket-ui test-temporal-orchestrator-restarts test-temporal-worker-heartbeat test-temporal-storage-restarts test-temporal-dependency-outages test-temporal-pipeline-delivery test-temporal-compose-cycle test-temporal-backpressure test-temporal-capacity-limits test-temporal-human-wait-rotation test-temporal-retention-rebuild test-temporal-network-partition test-sandbox-runtime test-sandbox-network mcp-shadow-campaign mcp-active-campaign mcp-shadow-report package config status restart logs urls temporal-status temporal-logs temporal-ui down clean

help:
	$(log-target)
	@echo -e "$(YELLOW)AI Software Factory prototype - Commandes :$(NC)"
	@echo -e "  $(CYAN)make init$(NC)       - create .env and .vault from their examples"
	@echo -e "  $(CYAN)make a2a-pki$(NC)    - verify the generated local A2A mTLS material"
	@echo -e "  $(CYAN)make a2a-pki-rotate$(NC) - rotate local A2A mTLS material with a recoverable backup"
	@echo -e "  $(CYAN)make a2a-secrets$(NC) - verify owner-only, role-isolated local A2A secrets"
	@echo -e "  $(CYAN)make a2a-supply-chain A2A_RUNTIME_IMAGE=...@sha256:...$(NC) - qualify the signed runtime image"
	@echo -e "  $(CYAN)make a2a-config$(NC) - validate A2A Compose topology and capability boundaries"
	@echo -e "  $(CYAN)make a2a-status$(NC) - show A2A database and role runtime status"
	@echo -e "  $(CYAN)make a2a-cards$(NC) - fetch and validate all private Agent Cards"
	@echo -e "  $(CYAN)make a2a-smoke$(NC) - require healthy services and valid Agent Cards"
	@echo -e "  $(CYAN)make a2a-logs$(NC) - follow A2A database and runtime logs"
	@echo -e "  $(CYAN)make a2a-reset-state CONFIRM_A2A_RESET=DELETE_A2A_LOCAL_STATE$(NC) - delete local A2A task state"
	@echo -e "  $(CYAN)make a2a-up-role A2A_ROLE=developer$(NC) - start one role test profile"
	@echo -e "  $(CYAN)make a2a-up-full$(NC) - start the complete A2A test profile"
	@echo -e "  $(CYAN)make test-a2a-security$(NC) - run the A2A adversarial security campaign"
	@echo -e "  $(CYAN)make test-a2a-performance$(NC) - benchmark A2A admission and bounded saturation"
	@echo -e "  $(CYAN)make build$(NC)      - build orchestrator + sandbox images"
	@echo -e "  $(CYAN)make up$(NC)         - start the complete local factory stack"
	@echo -e "  $(CYAN)make all$(NC)        - reset data and start a fully bootstrapped local factory"
	@echo -e "  $(CYAN)make bootstrap$(NC)  - initialize demo Gitea repository and service tokens"
	@echo -e "  $(CYAN)make bootstrap-signoz$(NC) - provision local SigNoz dashboards, channel and alerts"
	@echo -e "  $(CYAN)make tokens$(NC)     - validate or regenerate local Gitea and SonarQube tokens"
	@echo -e "  $(CYAN)make demo$(NC)       - submit an AI task against the demo repository"
	@echo -e "  $(CYAN)make test$(NC)       - run orchestrator and MCP server tests"
	@echo -e "  $(CYAN)make temporal-replay$(NC) - replay versioned histories before worker image build"
	@echo -e "  $(CYAN)make temporal-cutover-baseline$(NC) - verify the frozen pre-cutover pipeline baseline"
	@echo -e "  $(CYAN)make temporal-cutover-freeze$(NC) - reject drift in the qualified cutover scope"
	@echo -e "  $(CYAN)make qualify-temporal-cutover$(NC) - run the complete cutover qualification barrier"
	@echo -e "  $(CYAN)make monitor-temporal-cutover$(NC) - monitor the strengthened post-cutover window"
	@echo -e "  $(CYAN)make admissions-status$(NC) - show the durable ticket admission switch"
	@echo -e "  $(CYAN)make admissions-close$(NC) - reject new tickets during a maintenance window"
	@echo -e "  $(CYAN)make admissions-open$(NC) - reopen ticket admissions after verification"
	@echo -e "  $(CYAN)make backup-temporal-cutover BACKUP_DIR=...$(NC) - backup all cutover authorities"
	@echo -e "  $(CYAN)make restore-temporal-cutover BACKUP_DIR=... RESTORE_PREFIX=...$(NC) - verify an isolated restore"
	@echo -e "  $(CYAN)make test-temporal-compose$(NC) - verify local namespace, UI, readiness and all pollers"
	@echo -e "  $(CYAN)make test-temporal-ticket-ui$(NC) - submit a real ticket and verify its Temporal UI identity"
	@echo -e "  $(CYAN)make test-temporal-orchestrator-restarts$(NC) - recreate the orchestrator across critical phases"
	@echo -e "  $(CYAN)make test-temporal-worker-heartbeat$(NC) - SIGKILL a sandbox worker activity and verify resume"
	@echo -e "  $(CYAN)make test-temporal-storage-restarts$(NC) - restart Temporal and its PostgreSQL without data loss"
	@echo -e "  $(CYAN)make test-temporal-dependency-outages$(NC) - inject and recover all external dependency outages"
	@echo -e "  $(CYAN)make test-temporal-pipeline-delivery$(NC) - approve one full pipeline and verify exactly one PR"
	@echo -e "  $(CYAN)make test-temporal-compose-cycle$(NC) - verify tasks and histories survive Compose down/up"
	@echo -e "  $(CYAN)make test-temporal-backpressure$(NC) - overload a constrained worker and measure its backlog"
	@echo -e "  $(CYAN)make test-temporal-capacity-limits$(NC) - verify global and per-task-queue capacity guards"
	@echo -e "  $(CYAN)make test-temporal-human-wait-rotation$(NC) - keep an approval wait across restart and worker rotation"
	@echo -e "  $(CYAN)make test-temporal-retention-rebuild$(NC) - verify retention, evidence purge and projection rebuild"
	@echo -e "  $(CYAN)make test-sandbox-runtime$(NC) - verify the static Compose sandbox runner"
	@echo -e "  $(CYAN)make test-sandbox-network$(NC) - verify Compose runner network isolation"
	@echo -e "  $(CYAN)make mcp-shadow-campaign$(NC) - validate the 20-task campaign (set CAMPAIGN_ARGS=--execute to run)"
	@echo -e "  $(CYAN)make mcp-active-campaign$(NC) - run the role-scoped MCP_ACTIVE canary"
	@echo -e "  $(CYAN)make mcp-shadow-report$(NC) - generate the MCP shadow campaign report"
	@echo -e "  $(CYAN)make package$(NC)    - package orchestrator without tests"
	@echo -e "  $(CYAN)make config$(NC)     - validate and render Docker Compose configuration"
	@echo -e "  $(CYAN)make status$(NC)     - show containers"
	@echo -e "  $(CYAN)make restart$(NC)    - restart the orchestrator"
	@echo -e "  $(CYAN)make logs$(NC)       - follow orchestrator logs"
	@echo -e "  $(CYAN)make urls$(NC)       - list available service and API URLs"
	@echo -e "  $(CYAN)make temporal-status$(NC) - show Temporal infrastructure health and workflow pollers"
	@echo -e "  $(CYAN)make temporal-logs$(NC) - follow Temporal server, UI and orchestrator logs"
	@echo -e "  $(CYAN)make temporal-ui$(NC) - open the loopback-only Temporal UI"
	@echo -e "  $(CYAN)make down$(NC)       - stop stack"
	@echo -e "  $(CYAN)make clean$(NC)      - stop and remove volumes (destructive)"

init:
	$(log-target)
	@test -f .env || cp .env.example .env
	@test -f .vault || cp .vault.example .vault
	@./scripts/init-local-config.sh
	@echo -e "$(GREEN).env and .vault ready$(NC)"

a2a-pki:
	$(log-target)
	@test -d .local/a2a-pki || ./scripts/generate-a2a-local-pki.sh .local/a2a-pki
	@./scripts/verify-a2a-pki.sh .local/a2a-pki

a2a-pki-rotate:
	$(log-target)
	@./scripts/rotate-a2a-local-pki.sh .local/a2a-pki

a2a-secrets:
	$(log-target)
	@test -d .local/a2a-secrets || ./scripts/generate-a2a-local-secrets.sh .local/a2a-secrets
	@./scripts/verify-a2a-secrets.sh .local/a2a-secrets

a2a-supply-chain:
	$(log-target)
	@test -n "$(A2A_RUNTIME_IMAGE)" || (echo "A2A_RUNTIME_IMAGE digest is required" >&2; exit 1)
	@./scripts/qualify-a2a-runtime-image.sh "$(A2A_RUNTIME_IMAGE)"

a2a-config:
	$(log-target)
	@./scripts/a2a-local.sh config

a2a-status:
	$(log-target)
	@./scripts/a2a-local.sh status

a2a-cards:
	$(log-target)
	@./scripts/a2a-local.sh cards

a2a-smoke:
	$(log-target)
	@./scripts/a2a-local.sh smoke

a2a-logs:
	$(log-target)
	@./scripts/a2a-local.sh logs

a2a-reset-state:
	$(log-target)
	@./scripts/reset-a2a-local-state.sh

a2a-up-role:
	$(log-target)
	@test -n "$(A2A_ROLE)" || (echo "A2A_ROLE is required" >&2; exit 2)
	@A2A_ROLE="$(A2A_ROLE)" ./scripts/start-a2a-profile.sh role

a2a-up-full:
	$(log-target)
	@./scripts/start-a2a-profile.sh full

test-a2a-compose-integration:
	$(log-target)
	@./scripts/test-a2a-compose-integration.sh

test-a2a-compose-failures:
	$(log-target)
	@./scripts/test-a2a-compose-failures.sh

test-a2a-security:
	$(log-target)
	@./scripts/test-a2a-security.sh

test-a2a-performance:
	$(log-target)
	@./scripts/test-a2a-performance.sh

build: temporal-replay
	$(log-target)
	@echo -e "$(BLUE)Building sandbox and orchestrator images...$(NC)"
	@test -n "$(SYFT_VERSION)" || (echo "SYFT_VERSION must be defined in .env" >&2; exit 1)
	@test -n "$(TRIVY_VERSION)" || (echo "TRIVY_VERSION must be defined in .env" >&2; exit 1)
	@test -n "$(TRIVY_PRELOAD_DB)" || (echo "TRIVY_PRELOAD_DB must be defined in .env" >&2; exit 1)
	@test -n "$(NODE_VERSION)" || (echo "NODE_VERSION must be defined in .env" >&2; exit 1)
	docker build \
		--build-arg TRIVY_VERSION="$(TRIVY_VERSION)" \
		--build-arg SYFT_VERSION="$(SYFT_VERSION)" \
		--build-arg NODE_VERSION="$(NODE_VERSION)" \
		--build-arg TRIVY_PRELOAD_DB="$(TRIVY_PRELOAD_DB)" \
		-t ai-factory-sandbox:local ./infrastructure/sandbox
	./scripts/pin-sandbox-image.sh .env ai-factory-sandbox:local
	$(COMPOSE) build sandbox-egress-proxy repository-context-mcp sandbox-execution-mcp scm-delivery-mcp assurance-mcp evidence-mcp orchestrator factory-web signoz-clickhouse-init signoz-bootstrap
	@echo -e "$(GREEN)Build complete!$(NC)"

up: init build
	$(log-target)
	@echo -e "$(BLUE)Starting local factory stack...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --remove-orphans
	@./scripts/wait-compose-job.sh signoz-bootstrap 120
	@$(MAKE) a2a-smoke
	@echo -e "$(GREEN)Stack started!$(NC)"
	@$(MAKE) urls

all: init
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
	$(COMPOSE) up -d --force-recreate sandbox-execution-mcp scm-delivery-mcp orchestrator
	@echo -e "$(GREEN)Bootstrap complete!$(NC)"

bootstrap-signoz: init
	$(log-target)
	@echo -e "$(BLUE)Provisioning SigNoz dashboards and alerts...$(NC)"
	./scripts/bootstrap-signoz.sh
	@echo -e "$(GREEN)SigNoz provisioning complete!$(NC)"

tokens: init
	$(log-target)
	@echo -e "$(BLUE)Updating Gitea and SonarQube tokens...$(NC)"
	./scripts/bootstrap-gitea.sh --token-only
	./scripts/bootstrap-sonar.sh
	$(COMPOSE) up -d --force-recreate scm-delivery-mcp orchestrator
	@echo -e "$(GREEN)Tokens updated!$(NC)"

demo:
	$(log-target)
	@echo -e "$(BLUE)Submitting demo task...$(NC)"
	./scripts/demo.sh

test:
	$(log-target)
	@echo -e "$(BLUE)Running orchestrator and MCP server tests...$(NC)"
	./scripts/check-no-docker-socket.sh
	./scripts/test-a2a-pki.sh
	./scripts/test-a2a-pki-rotation.sh
	./scripts/test-a2a-secret-rotation.sh
	./scripts/verify-a2a-threat-model.rb
	./scripts/test-a2a-supply-chain-policy.sh
	ruby ./scripts/verify-a2a-compose-runtime.rb
	ruby ./scripts/verify-a2a-compose-network.rb
	ruby ./scripts/verify-a2a-mcp-networks.rb
	./scripts/verify-env-structure.sh
	./scripts/test-a2a-compose-profiles.sh
	./scripts/test-a2a-compose-persistence.sh
	./scripts/generate-a2a-gke-manifests.rb infrastructure/gke/a2a/agents.generated.yaml --check
	ruby ./scripts/verify-a2a-gke-manifests.rb
	ruby ./scripts/verify-a2a-gke-discovery.rb
	ruby ./scripts/verify-a2a-private-entry.rb
	if [ -x ./apps/orchestrator/mvnw ]; then ./apps/orchestrator/mvnw $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml clean test; else mvn $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml clean test; fi
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/repository-context-server/pom.xml clean test
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/sandbox-execution-server/pom.xml clean test
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/scm-delivery-server/pom.xml clean test
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/assurance-server/pom.xml clean test
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/evidence-server/pom.xml clean test

temporal-replay:
	$(log-target)
	@echo -e "$(BLUE)Replaying versioned Temporal histories...$(NC)"
	@if [ -x ./apps/orchestrator/mvnw ]; then ./apps/orchestrator/mvnw $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml test -Dtest=WorkflowDeterminismArchitectureTest; else mvn $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml test -Dtest=WorkflowDeterminismArchitectureTest; fi
	@echo -e "$(GREEN)Temporal histories are replay-compatible.$(NC)"

temporal-cutover-baseline:
	$(log-target)
	@ruby scripts/verify-pipeline-baseline.rb

temporal-cutover-freeze:
	$(log-target)
	@ruby scripts/verify-temporal-cutover-freeze.rb

qualify-temporal-cutover:
	$(log-target)
	@./scripts/qualify-temporal-cutover.sh

admissions-status:
	$(log-target)
	@./scripts/set-admissions.sh status

admissions-close:
	$(log-target)
	@./scripts/set-admissions.sh close

admissions-open:
	$(log-target)
	@./scripts/set-admissions.sh open

backup-temporal-cutover:
	$(log-target)
	@test -n "$(BACKUP_DIR)" || (echo "BACKUP_DIR is required" >&2; exit 2)
	@./scripts/backup-temporal-cutover.sh "$(BACKUP_DIR)"

restore-temporal-cutover:
	$(log-target)
	@test -n "$(BACKUP_DIR)" || (echo "BACKUP_DIR is required" >&2; exit 2)
	@test -n "$(RESTORE_PREFIX)" || (echo "RESTORE_PREFIX is required" >&2; exit 2)
	@./scripts/restore-temporal-cutover-isolated.sh "$(BACKUP_DIR)" "$(RESTORE_PREFIX)"

monitor-temporal-cutover:
	$(log-target)
	@./scripts/monitor-temporal-cutover.sh

test-temporal-compose:
	$(log-target)
	@./scripts/test-temporal-compose-readiness.sh

test-temporal-ticket-ui:
	$(log-target)
	@./scripts/test-temporal-ticket-ui.sh

test-temporal-orchestrator-restarts:
	$(log-target)
	@./scripts/test-temporal-orchestrator-restarts.sh

test-temporal-worker-heartbeat:
	$(log-target)
	@./scripts/test-temporal-worker-heartbeat.sh

test-temporal-storage-restarts:
	$(log-target)
	@./scripts/test-temporal-storage-restarts.sh

test-temporal-dependency-outages:
	$(log-target)
	@./scripts/test-temporal-dependency-outages.sh

test-temporal-pipeline-delivery:
	$(log-target)
	@./scripts/test-temporal-pipeline-delivery.sh

test-temporal-compose-cycle:
	$(log-target)
	@./scripts/test-temporal-compose-cycle.sh

test-temporal-backpressure:
	$(log-target)
	@./scripts/test-temporal-backpressure.sh

test-temporal-capacity-limits:
	$(log-target)
	@if [ -x ./apps/orchestrator/mvnw ]; then ./apps/orchestrator/mvnw $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml test -Dtest=TemporalPropertiesTest,TemporalWorkerRegistryTest,TemporalTaskQueueMonitorTest; else mvn $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml test -Dtest=TemporalPropertiesTest,TemporalWorkerRegistryTest,TemporalTaskQueueMonitorTest; fi
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/sandbox-execution-server/pom.xml test -Dtest=SandboxExecutionPropertiesTest,SandboxJobServiceTest

test-temporal-human-wait-rotation:
	$(log-target)
	@./scripts/test-temporal-human-wait-rotation.sh

test-temporal-retention-rebuild:
	$(log-target)
	@./scripts/test-temporal-retention.sh 7
	@if [ -x ./apps/orchestrator/mvnw ]; then ./apps/orchestrator/mvnw $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml test -Dtest=ProjectionRebuilderTest,ProjectionRebuildCommandTest,ArtifactLifecyclePolicyTest; else mvn $(MAVEN_HOST_SETTINGS) -f apps/orchestrator/pom.xml test -Dtest=ProjectionRebuilderTest,ProjectionRebuildCommandTest,ArtifactLifecyclePolicyTest; fi
	mvn $(MAVEN_HOST_SETTINGS) -f apps/mcp/evidence-server/pom.xml test -Dtest=EvidenceStoreTest

test-temporal-network-partition:
	$(log-target)
	@./scripts/test-temporal-network-partition.sh

test-sandbox-runtime:
	$(log-target)
	@echo -e "$(BLUE)Verifying the static Compose sandbox runner...$(NC)"
	python3 -m unittest infrastructure/sandbox/test_runner.py
	@echo -e "$(GREEN)Compose sandbox runner verified!$(NC)"

test-sandbox-network:
	$(log-target)
	@echo -e "$(BLUE)Verifying Compose sandbox network isolation...$(NC)"
	./scripts/check-sandbox-network-isolation.sh
	@echo -e "$(GREEN)Compose sandbox network isolation verified!$(NC)"

mcp-shadow-campaign:
	$(log-target)
	./scripts/mcp-context-shadow-campaign.sh $(CAMPAIGN_ARGS)

mcp-active-campaign:
	$(log-target)
	AI_FACTORY_CONTEXT_CAMPAIGN_KIND=active ./scripts/mcp-context-shadow-campaign.sh $(CAMPAIGN_ARGS)

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

temporal-status:
	$(log-target)
	@echo -e "$(BLUE)Temporal infrastructure:$(NC)"
	$(COMPOSE) ps temporal temporal-namespace temporal-ui
	$(COMPOSE) run --rm --no-deps --entrypoint temporal temporal-namespace operator cluster health --address temporal:7233
	@echo -e "$(BLUE)Ticket engine pollers ($(AI_FACTORY_TEMPORAL_WORKFLOW_TASK_QUEUE)):$(NC)"
	$(COMPOSE) run --rm --no-deps --entrypoint temporal temporal-namespace task-queue describe --namespace "$(AI_FACTORY_TEMPORAL_NAMESPACE)" --task-queue "$(AI_FACTORY_TEMPORAL_WORKFLOW_TASK_QUEUE)" --address temporal:7233

temporal-logs:
	$(log-target)
	$(COMPOSE) logs --tail=200 -f temporal temporal-namespace temporal-ui orchestrator

temporal-ui:
	$(log-target)
	@echo -e "$(GREEN)http://$(TEMPORAL_UI_BIND_ADDRESS):$(TEMPORAL_UI_PORT)$(NC)"
	@open "http://$(TEMPORAL_UI_BIND_ADDRESS):$(TEMPORAL_UI_PORT)"

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
	@echo -e "  - Metrics:      $(GREEN)http://localhost:$(ORCHESTRATOR_PORT)/actuator/metrics$(NC)"
	@echo ""
	@echo -e "$(YELLOW)Quality, Artifacts & Observability:$(NC)"
	@echo -e "  - SonarQube:    $(GREEN)http://localhost:$(SONAR_PORT)$(NC) (user: $(SONAR_ADMIN_LOGIN), password: $(SONAR_ADMIN_PASSWORD))"
	@echo -e "  - Artifactory:  $(GREEN)http://localhost:$(ARTIFACTORY_PORT)$(NC) (user: admin, password: password)"
	@echo -e "  - SigNoz:       $(GREEN)http://localhost:$(SIGNOZ_PORT)$(NC) (user: $(SIGNOZ_ROOT_EMAIL), password: $(SIGNOZ_ROOT_PASSWORD))"
	@echo -e "  - Temporal UI:  $(GREEN)http://$(TEMPORAL_UI_BIND_ADDRESS):$(TEMPORAL_UI_PORT)$(NC)"
	@echo ""

clean:
	$(log-target)
	@echo -e "$(RED)Cleaning stack and removing volumes...$(NC)"
	$(COMPOSE) down -v --remove-orphans
	@echo -e "$(GREEN)Clean complete!$(NC)"
