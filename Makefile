SHELL := /bin/bash

-include .env

ORCHESTRATOR_PORT ?= 8088
WEB_APP_PORT ?= 8080
GITEA_HTTP_PORT ?= 3000
GITEA_SSH_PORT ?= 2222
OLLAMA_PORT ?= 11434
SONAR_PORT ?= 9000
NEXUS_PORT ?= 8081
PROMETHEUS_PORT ?= 9090
GRAFANA_PORT ?= 3001

.PHONY: help init build up full model bootstrap demo test package config status restart logs urls down clean

help:
	@echo "AI Software Factory local prototype"
	@echo "  make init       - create .env from .env.example"
	@echo "  make build      - build orchestrator + sandbox images"
	@echo "  make up         - start core stack (web app, Gitea, Ollama, orchestrator)"
	@echo "  make full       - start core + SonarQube, Nexus, Prometheus, Grafana"
	@echo "  make model      - pull configured Ollama model"
	@echo "  make bootstrap  - create demo Gitea user/repository and push sample app"
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
	@echo ".env ready"

build:
	docker build -t ai-factory-sandbox:local ./sandbox
	docker compose build orchestrator factory-web

up: init build
	docker compose up -d gitea-db gitea ollama orchestrator factory-web
	$(MAKE) urls

full: init build
	docker compose --profile full up -d

model:
	docker compose exec ollama ollama pull $${OLLAMA_MODEL:-qwen2.5-coder:7b}

bootstrap:
	./scripts/bootstrap-gitea.sh

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
	docker compose --profile full down

logs:
	docker compose logs -f orchestrator

urls:
	@echo "Core"
	@echo "  Factory web:  http://localhost:$(WEB_APP_PORT)"
	@echo "  Gitea:        http://localhost:$(GITEA_HTTP_PORT)"
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
	@echo "Full profile (make full)"
	@echo "  SonarQube:    http://localhost:$(SONAR_PORT)"
	@echo "  Nexus:        http://localhost:$(NEXUS_PORT)"
	@echo "  Prometheus:   http://localhost:$(PROMETHEUS_PORT)"
	@echo "  Grafana:      http://localhost:$(GRAFANA_PORT)"

clean:
	docker compose --profile full down -v --remove-orphans
