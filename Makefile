SHELL := /bin/bash

-include .vault
-include .env

export VAULT_OPENAI_API_KEY

ORCHESTRATOR_PORT ?= 8088
WEB_APP_PORT ?= 8080
GITEA_HTTP_PORT ?= 3000
GITEA_SSH_PORT ?= 2222
OLLAMA_PORT ?= 11434
SONAR_PORT ?= 9000
NEXUS_PORT ?= 8081
PROMETHEUS_PORT ?= 9090
GRAFANA_PORT ?= 3001
GITEA_ADMIN_USER ?= aiadmin
GITEA_ADMIN_PASSWORD ?= ChangeMe123!
SONAR_ADMIN_LOGIN ?= admin
SONAR_ADMIN_PASSWORD ?= admin
GRAFANA_ADMIN_USER ?= admin
GRAFANA_ADMIN_PASSWORD ?= admin

.PHONY: help init build up full model bootstrap demo test package config status restart logs urls down clean

help:
	@echo "AI Software Factory local prototype"
	@echo "  make init       - create .env and .vault from their examples"
	@echo "  make build      - build orchestrator + sandbox images"
	@echo "  make up         - start the complete local factory stack"
	@echo "  make full       - reset data and start a fully bootstrapped local factory"
	@echo "  make model      - pull configured Ollama model"
	@echo "  make bootstrap  - initialize demo Gitea repository and service tokens"
	@echo "  make demo       - submit an AI task against the demo repository"
	@echo "  make test       - run orchestrator tests"
	@echo "  make package    - package orchestrator without tests"
	@echo "  make config     - validate and render Docker Compose configuration"
	@echo "  make status     - show containers"
	@echo "  make restart    - restart the orchestrator"
	@echo "  make logs       - follow orchestrator logs"
	@echo "  make urls       - list available service and API URLs"
	@echo "  make down       - stop stack"
	@echo "  make clean      - stop and remove volumes (destructive)"

init:
	@test -f .env || cp .env.example .env
	@test -f .vault || cp .vault.example .vault
	@echo ".env and .vault ready"

build:
	docker build -t ai-factory-sandbox:local ./sandbox
	docker compose build orchestrator factory-web

up: init build
	docker compose up -d
	$(MAKE) urls

full:
	$(MAKE) clean
	$(MAKE) up
	$(MAKE) model
	$(MAKE) bootstrap

model:
	docker compose exec ollama ollama pull $${OLLAMA_MODEL:-qwen2.5-coder:7b}

bootstrap: init
	./scripts/bootstrap-gitea.sh
	./scripts/bootstrap-sonar.sh
	docker compose up -d --force-recreate orchestrator

demo:
	./scripts/demo.sh

test:
	if [ -x ./mvnw ]; then ./mvnw -f orchestrator/pom.xml test; else mvn -f orchestrator/pom.xml test; fi

package:
	if [ -x ./mvnw ]; then ./mvnw -f orchestrator/pom.xml package -DskipTests; else mvn -f orchestrator/pom.xml package -DskipTests; fi

config:
	docker compose config >/dev/null

restart:
	docker compose restart orchestrator

status:
	docker compose ps

down:
	docker compose down

logs:
	docker compose logs -f orchestrator

urls:
	@echo "Core"
	@echo "  Factory web:  http://localhost:$(WEB_APP_PORT)"
	@echo "  Gitea:        http://localhost:$(GITEA_HTTP_PORT) (user: $(GITEA_ADMIN_USER), password: $(GITEA_ADMIN_PASSWORD))"
	@echo "  Gitea API:    http://localhost:$(GITEA_HTTP_PORT)/api/v1"
	@echo "  Gitea SSH:    ssh://git@localhost:$(GITEA_SSH_PORT)"
	@echo "  Demo repo:    http://localhost:$(GITEA_HTTP_PORT)/$${GITEA_ADMIN_USER:-aiadmin}/customer-api"
	@echo "  Ollama API:   http://localhost:$(OLLAMA_PORT)"
	@echo "  Orchestrator: http://localhost:$(ORCHESTRATOR_PORT)"
	@echo "  Tasks API:    http://localhost:$(ORCHESTRATOR_PORT)/api/tasks"
	@echo "  Create task:  POST http://localhost:$(ORCHESTRATOR_PORT)/api/tasks"
	@echo "  Task detail:  GET  http://localhost:$(ORCHESTRATOR_PORT)/api/tasks/<TASK_ID>"
	@echo "  Approve task: POST http://localhost:$(ORCHESTRATOR_PORT)/api/tasks/<TASK_ID>/approve"
	@echo "  Actuator:     http://localhost:$(ORCHESTRATOR_PORT)/actuator"
	@echo "  Health:       http://localhost:$(ORCHESTRATOR_PORT)/actuator/health"
	@echo "  Metrics:      http://localhost:$(ORCHESTRATOR_PORT)/actuator/prometheus"
	@echo "Quality, artifacts and observability"
	@echo "  SonarQube:    http://localhost:$(SONAR_PORT) (user: $(SONAR_ADMIN_LOGIN), password: $(SONAR_ADMIN_PASSWORD))"
	@echo "  Nexus:        http://localhost:$(NEXUS_PORT) (user: admin, initial password: docker compose exec nexus cat /nexus-data/admin.password)"
	@echo "  Prometheus:   http://localhost:$(PROMETHEUS_PORT)"
	@echo "  Grafana:      http://localhost:$(GRAFANA_PORT) (user: $(GRAFANA_ADMIN_USER), initial password: $(GRAFANA_ADMIN_PASSWORD))"

clean:
	docker compose down -v --remove-orphans
