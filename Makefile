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

.PHONY: help init factory-core-up verify-ready a2a-pki a2a-pki-rotate a2a-secrets a2a-supply-chain a2a-worker-drainage a2a-rollback-gate a2a-config a2a-status a2a-cards a2a-smoke a2a-logs a2a-reset-state a2a-up-role a2a-up-full monitor-a2a-cutover a2a-evidence-manifest check-a2a-evidence-manifest check-a2a-cutover-approval test-a2a-temporal test-a2a-compose-integration test-a2a-compose-failures test-a2a-security test-a2a-performance test-a2a-e2e-parity test-a2a-rollback-load test-a2a-rollback-gate build build-images up all bootstrap bootstrap-signoz tokens demo test temporal-replay temporal-cutover-baseline temporal-cutover-freeze qualify-temporal-cutover admissions-status admissions-close admissions-open backup-temporal-cutover restore-temporal-cutover monitor-temporal-cutover test-temporal-compose test-temporal-ticket-ui test-temporal-orchestrator-restarts test-temporal-worker-heartbeat test-temporal-storage-restarts test-temporal-dependency-outages test-temporal-pipeline-delivery test-temporal-compose-cycle test-temporal-backpressure test-temporal-capacity-limits test-temporal-human-wait-rotation test-temporal-retention-rebuild test-temporal-network-partition test-sandbox-runtime test-sandbox-network mcp-shadow-campaign mcp-active-campaign mcp-shadow-report package config status restart logs urls temporal-status temporal-logs temporal-ui down clean

help:
	$(log-target)
	@echo -e "$(YELLOW)AI Software Factory prototype - Commandes :$(NC)"
	@echo -e "  $(CYAN)make init$(NC)       - create .env and .vault from their examples"
	@echo -e "  $(CYAN)make a2a-pki$(NC)    - verify the generated local A2A mTLS material"
	@echo -e "  $(CYAN)make a2a-pki-rotate$(NC) - rotate local A2A mTLS material with a recoverable backup"
	@echo -e "  $(CYAN)make a2a-secrets$(NC) - verify owner-only, role-isolated local A2A secrets"
	@echo -e "  $(CYAN)make a2a-supply-chain A2A_RUNTIME_IMAGE=...@sha256:...$(NC) - qualify the signed runtime image"
	@echo -e "  $(CYAN)make a2a-worker-drainage BUILD_ID=...$(NC) - require a retired Temporal build to be drained"
	@echo -e "  $(CYAN)make a2a-rollback-gate ROLLBACK_GATE=...$(NC) - verify an operator-approved rollback manifest"
	@echo -e "  $(CYAN)make a2a-config$(NC) - validate A2A Compose topology and capability boundaries"
	@echo -e "  $(CYAN)make a2a-status$(NC) - show A2A database and role runtime status"
	@echo -e "  $(CYAN)make a2a-cards$(NC) - fetch and validate all private Agent Cards"
	@echo -e "  $(CYAN)make a2a-smoke$(NC) - require healthy services and valid Agent Cards"
	@echo -e "  $(CYAN)make a2a-logs$(NC) - follow A2A database and runtime logs"
	@echo -e "  $(CYAN)make a2a-reset-state CONFIRM_A2A_RESET=DELETE_A2A_LOCAL_STATE$(NC) - delete local A2A task state"
	@echo -e "  $(CYAN)make a2a-up-role A2A_ROLE=developer$(NC) - start one role test profile"
	@echo -e "  $(CYAN)make a2a-up-full$(NC) - start the complete A2A test profile"
	@echo -e "  $(CYAN)make test-a2a-temporal$(NC) - verify the A2A Build ID and all 28 Temporal poller sets"
	@echo -e "  $(CYAN)make verify-ready$(NC) - enforce the final full-factory readiness barrier"
	@echo -e "  $(CYAN)make monitor-a2a-cutover$(NC) - monitor the fail-closed A2A stabilization window"
	@echo -e "  $(CYAN)make a2a-evidence-manifest$(NC) - regenerate the digest manifest for all A2A evidence"
	@echo -e "  $(CYAN)make check-a2a-evidence-manifest$(NC) - reject a missing or stale A2A evidence digest"
	@echo -e "  $(CYAN)make check-a2a-cutover-approval$(NC) - verify the exact human sign-off and evidence archive"
	@echo -e "  $(CYAN)make test-a2a-security$(NC) - run the A2A adversarial security campaign"
	@echo -e "  $(CYAN)make test-a2a-performance$(NC) - benchmark A2A admission and bounded saturation"
	@echo -e "  $(CYAN)make test-a2a-e2e-parity$(NC) - replay business fixtures against the pre-cutover baseline"
	@echo -e "  $(CYAN)make test-a2a-rollback-load$(NC) - verify A2A rollback recovery under concurrent lifecycle load"
	@echo -e "  $(CYAN)make build$(NC)      - build every image required by the A2A factory"
	@echo -e "  $(CYAN)make up$(NC)         - start the complete local factory stack"
	@echo -e "  $(CYAN)make all$(NC)        - reset Docker data and start a fully verified A2A factory"
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
	@echo -e "  $(CYAN)make restart$(NC)    - recreate and verify the Temporal/A2A orchestrator"
	@echo -e "  $(CYAN)make logs$(NC)       - follow orchestrator logs"
	@echo -e "  $(CYAN)make urls$(NC)       - list available service and API URLs"
	@echo -e "  $(CYAN)make temporal-status$(NC) - show Temporal infrastructure health and workflow pollers"
	@echo -e "  $(CYAN)make temporal-logs$(NC) - follow Temporal server, UI and orchestrator logs"
	@echo -e "  $(CYAN)make temporal-ui$(NC) - open the loopback-only Temporal UI"
	@echo -e "  $(CYAN)make down$(NC)       - stop stack"
	@echo -e "  $(CYAN)make clean$(NC)      - stop and remove volumes (destructive)"

init:
	$(log-target)
	@echo -e "$(BLUE)[init 1/2] Ensuring local environment and vault files exist...$(NC)"
	@test -f .env || cp .env.example .env
	@test -f .vault || cp .vault.example .vault
	@echo -e "$(BLUE)[init 2/2] Generating or validating local secrets, A2A PKI and card trust...$(NC)"
	@./scripts/init-local-config.sh
	@echo -e "$(GREEN).env and .vault ready$(NC)"

# Start only the dependencies needed before the isolated A2A runtimes. The orchestrator is intentionally
# excluded here: its fail-closed readiness requires the complete agent fleet to be reachable first.
factory-core-up: init
	$(log-target)
	@echo -e "$(BLUE)Starting Temporal, LLM and mandatory MCP dependencies for the A2A fleet...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --wait --wait-timeout 300 temporal-namespace litellm repository-context-mcp evidence-mcp
	@echo -e "$(GREEN)A2A runtime prerequisites are healthy.$(NC)"

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
	@./scripts/sync-a2a-card-trust.rb .local/a2a-secrets
	@./scripts/verify-a2a-secrets.sh .local/a2a-secrets

a2a-supply-chain:
	$(log-target)
	@test -n "$(A2A_RUNTIME_IMAGE)" || (echo "A2A_RUNTIME_IMAGE digest is required" >&2; exit 1)
	@./scripts/qualify-a2a-runtime-image.sh "$(A2A_RUNTIME_IMAGE)"

a2a-worker-drainage:
	$(log-target)
	@test -n "$(BUILD_ID)" || (echo "BUILD_ID is required" >&2; exit 2)
	@./scripts/a2a-worker-drainage.sh "$(BUILD_ID)"

a2a-rollback-gate:
	$(log-target)
	@test -n "$(ROLLBACK_GATE)" || (echo "ROLLBACK_GATE is required" >&2; exit 2)
	@ruby ./scripts/verify-a2a-rollback-gate.rb "$(ROLLBACK_GATE)"

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

a2a-up-role: init
	$(log-target)
	@test -n "$(A2A_ROLE)" || (echo "A2A_ROLE is required" >&2; exit 2)
	@echo -e "$(BLUE)[A2A role 1/3] Validating the Compose topology...$(NC)"
	@$(MAKE) a2a-config
	@echo -e "$(BLUE)[A2A role 2/3] Starting shared runtime dependencies...$(NC)"
	@$(MAKE) factory-core-up
	@echo -e "$(BLUE)[A2A role 3/3] Starting and validating role $(A2A_ROLE)...$(NC)"
	@A2A_ROLE="$(A2A_ROLE)" ./scripts/start-a2a-profile.sh role
	@echo -e "$(GREEN)A2A role $(A2A_ROLE) is ready.$(NC)"

a2a-up-full: init
	$(log-target)
	@echo -e "$(BLUE)[A2A fleet 1/3] Validating the complete topology...$(NC)"
	@$(MAKE) a2a-config
	@echo -e "$(BLUE)[A2A fleet 2/3] Starting shared runtime dependencies...$(NC)"
	@$(MAKE) factory-core-up
	@echo -e "$(BLUE)[A2A fleet 3/3] Starting 14 isolated runtimes in bounded batches...$(NC)"
	@./scripts/start-a2a-profile.sh full
	@echo -e "$(GREEN)Complete A2A fleet is healthy and activated in Temporal.$(NC)"

# This is the single success barrier used after startup, bootstrap, token rotation and orchestrator restart.
# It never opens admissions: an operator-closed factory remains closed and the target fails explicitly.
verify-ready:
	$(log-target)
	@echo -e "$(BLUE)[readiness 1/5] Waiting for one-shot provisioning and worker activation jobs...$(NC)"
	@./scripts/wait-compose-job.sh signoz-bootstrap 120
	@./scripts/wait-compose-job.sh temporal-worker-activation 120
	@./scripts/wait-compose-job.sh a2a-worker-activation 120
	@echo -e "$(BLUE)[readiness 2/5] Verifying all A2A services, identities and Agent Cards...$(NC)"
	@$(MAKE) a2a-smoke
	@echo -e "$(BLUE)[readiness 3/5] Verifying the Temporal control-plane queues...$(NC)"
	@$(MAKE) test-temporal-compose
	@echo -e "$(BLUE)[readiness 4/5] Verifying all A2A Temporal queues and pollers...$(NC)"
	@$(MAKE) test-a2a-temporal
	@echo -e "$(BLUE)[readiness 5/5] Verifying that normal ticket admissions are open...$(NC)"
	@curl -fsS --max-time 10 "http://127.0.0.1:$(ORCHESTRATOR_PORT)/api/capabilities" | jq -e '.admissionsOpen == true and .admissionReason == "normal_operation"' >/dev/null
	@echo -e "$(GREEN)Full factory readiness barrier passed.$(NC)"

monitor-a2a-cutover:
	$(log-target)
	@./scripts/monitor-a2a-cutover.sh

a2a-evidence-manifest:
	$(log-target)
	@ruby ./scripts/generate-a2a-evidence-manifest.rb

check-a2a-evidence-manifest:
	$(log-target)
	@ruby ./scripts/generate-a2a-evidence-manifest.rb --check

check-a2a-cutover-approval:
	$(log-target)
	@ruby ./scripts/verify-a2a-cutover-approval.rb

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

test-a2a-e2e-parity:
	$(log-target)
	@./scripts/test-a2a-e2e-parity.sh

test-a2a-rollback-load:
	$(log-target)
	@./scripts/test-a2a-rollback-load.sh

test-a2a-rollback-gate:
	$(log-target)
	@./scripts/test-a2a-rollback-gate.sh

test-a2a-temporal:
	$(log-target)
	@./scripts/test-a2a-temporal-readiness.sh

# Always cross a recursive Make boundary after init so a first invocation without .env reloads the newly
# generated variables before Docker build arguments are expanded.
build: init
	$(log-target)
	@echo -e "$(BLUE)Local configuration is ready; starting the reproducible image build...$(NC)"
	@$(MAKE) build-images

# Build every locally maintained image required by a fresh A2A factory. Building one agent service is
# intentional: all fourteen roles share the exact same immutable runtime image.
build-images: a2a-config temporal-replay
	$(log-target)
	@echo -e "$(BLUE)[build 1/2] Building and pinning the socket-free sandbox image...$(NC)"
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
	@echo -e "$(BLUE)[build 2/2] Building application, MCP, A2A identity/runtime and observability images...$(NC)"
	$(COMPOSE) --profile a2a-full build litellm sandbox-egress-proxy repository-context-mcp sandbox-execution-mcp scm-delivery-mcp assurance-mcp evidence-mcp a2a-identity a2a-supervisor orchestrator factory-web signoz-clickhouse-init signoz-bootstrap
	@echo -e "$(GREEN)Build complete!$(NC)"

# Start the qualified A2A path first, then materialize the rest of the local product stack. The bounded fleet
# starter waits for role health and explicitly activates both Temporal worker deployments.
up: init
	$(log-target)
	@echo -e "$(BLUE)[startup 1/4] Building all images required by the complete factory...$(NC)"
	@$(MAKE) build
	@echo -e "$(BLUE)[startup 2/4] Starting and activating the complete A2A fleet...$(NC)"
	@$(MAKE) a2a-up-full
	@echo -e "$(BLUE)[startup 3/4] Starting the remaining UI, quality and observability services...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --remove-orphans
	@echo -e "$(BLUE)[startup 4/4] Running the complete readiness barrier...$(NC)"
	@$(MAKE) verify-ready
	@echo -e "$(GREEN)Stack started and verified!$(NC)"
	@$(MAKE) urls

# A factory reset deliberately removes Docker data volumes but preserves operator-owned .env/.vault files,
# A2A certificates and role secrets. This keeps stable local identities while rebuilding every service and store.
all: init
	$(log-target)
	@echo -e "$(YELLOW)[factory 1/4] Removing all Docker services and persistent data volumes...$(NC)"
	$(MAKE) clean
	@echo -e "$(BLUE)[factory 2/4] Rebuilding and starting the complete A2A factory...$(NC)"
	$(MAKE) up
	@echo -e "$(BLUE)[factory 3/4] Bootstrapping repositories and quality credentials...$(NC)"
	$(MAKE) bootstrap
	@echo -e "$(BLUE)[factory 4/4] Bootstrap readiness barrier confirmed the final state.$(NC)"
	@echo -e "$(GREEN)Full A2A factory ready from empty Docker data volumes!$(NC)"

# Bootstrap mutates Gitea/Sonar credentials and therefore recreates control-plane consumers. Readiness and
# worker activation must be re-established after that recreation, not assumed from the earlier startup smoke.
bootstrap: init
	$(log-target)
	@echo -e "$(BLUE)[bootstrap 1/4] Creating demo repositories and refreshing the Gitea token...$(NC)"
	./scripts/bootstrap-gitea.sh
	@echo -e "$(BLUE)[bootstrap 2/4] Creating or validating the SonarQube token...$(NC)"
	./scripts/bootstrap-sonar.sh
	@echo -e "$(BLUE)[bootstrap 3/4] Recreating credential consumers and waiting for health...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --wait --wait-timeout 300 --no-deps --force-recreate sandbox-execution-mcp scm-delivery-mcp orchestrator
	@echo -e "$(BLUE)[bootstrap 4/4] Reactivating Temporal workers and revalidating the full factory...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --no-deps --force-recreate temporal-worker-activation a2a-worker-activation
	@$(MAKE) verify-ready
	@echo -e "$(GREEN)Bootstrap complete and factory readiness restored!$(NC)"

bootstrap-signoz: init
	$(log-target)
	@echo -e "$(BLUE)Provisioning SigNoz dashboards and alerts...$(NC)"
	./scripts/bootstrap-signoz.sh
	@echo -e "$(GREEN)SigNoz provisioning complete!$(NC)"

tokens: init
	$(log-target)
	@echo -e "$(BLUE)[tokens 1/3] Updating Gitea and SonarQube tokens...$(NC)"
	./scripts/bootstrap-gitea.sh --token-only
	./scripts/bootstrap-sonar.sh
	@echo -e "$(BLUE)[tokens 2/3] Recreating token consumers and waiting for health...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --wait --wait-timeout 300 --no-deps --force-recreate scm-delivery-mcp orchestrator
	@echo -e "$(BLUE)[tokens 3/3] Reactivating workers and verifying the full factory...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --no-deps --force-recreate temporal-worker-activation a2a-worker-activation
	@$(MAKE) verify-ready
	@echo -e "$(GREEN)Tokens updated and factory readiness restored!$(NC)"

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
	@echo -e "$(BLUE)Validating the complete Docker Compose and A2A configuration...$(NC)"
	$(COMPOSE) --profile a2a-full config >/dev/null
	@$(MAKE) a2a-config
	@echo -e "$(GREEN)Complete configuration is valid!$(NC)"

restart:
	$(log-target)
	@echo -e "$(YELLOW)[restart 1/3] Recreating the orchestrator and waiting for readiness...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --wait --wait-timeout 300 --force-recreate orchestrator
	@echo -e "$(BLUE)[restart 2/3] Reactivating its Temporal worker Build ID...$(NC)"
	$(COMPOSE) --profile a2a-full up -d --no-deps --force-recreate temporal-worker-activation
	@./scripts/wait-compose-job.sh temporal-worker-activation 120
	@echo -e "$(BLUE)[restart 3/3] Checking Temporal and A2A connectivity...$(NC)"
	@$(MAKE) a2a-smoke
	@$(MAKE) test-temporal-compose
	@$(MAKE) test-a2a-temporal
	@echo -e "$(GREEN)Orchestrator restarted and verified!$(NC)"

status:
	$(log-target)
	@echo -e "$(BLUE)Showing long-running services and one-shot A2A/Temporal jobs...$(NC)"
	$(COMPOSE) --profile a2a-full ps -a

down:
	$(log-target)
	@echo -e "$(YELLOW)Stopping the complete local factory, including all A2A profiles...$(NC)"
	$(COMPOSE) --profile a2a-full down --remove-orphans
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
	@echo -e "$(BLUE)A2A agent worker deployment ($(AI_FACTORY_A2A_TEMPORAL_DEPLOYMENT)):$(NC)"
	$(COMPOSE) run --rm --no-deps --entrypoint temporal temporal-namespace worker deployment describe --namespace "$(AI_FACTORY_TEMPORAL_NAMESPACE)" --name "$(AI_FACTORY_A2A_TEMPORAL_DEPLOYMENT)" --address temporal:7233

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
	@echo -e "  - Gitea:        $(GREEN)http://localhost:$(GITEA_HTTP_PORT)$(NC)"
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
	@echo -e "  - SonarQube:    $(GREEN)http://localhost:$(SONAR_PORT)$(NC)"
	@echo -e "  - Artifactory:  $(GREEN)http://localhost:$(ARTIFACTORY_PORT)$(NC)"
	@echo -e "  - SigNoz:       $(GREEN)http://localhost:$(SIGNOZ_PORT)$(NC)"
	@echo -e "  - Temporal UI:  $(GREEN)http://$(TEMPORAL_UI_BIND_ADDRESS):$(TEMPORAL_UI_PORT)$(NC)"
	@echo -e "$(YELLOW)Credentials remain in local configuration files and are never printed by this target.$(NC)"
	@echo ""

clean:
	$(log-target)
	@echo -e "$(RED)Removing the complete stack and every Docker data volume, including A2A task state...$(NC)"
	@echo -e "$(YELLOW)Local .env/.vault files, A2A PKI and role secrets are intentionally preserved.$(NC)"
	$(COMPOSE) --profile a2a-full down -v --remove-orphans
	@echo -e "$(GREEN)Docker data reset complete!$(NC)"
