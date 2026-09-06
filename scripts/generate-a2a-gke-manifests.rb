#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

output = ARGV.fetch(0, "infrastructure/gke/a2a/agents.generated.yaml")
roles = %w[
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
]
context_roles = roles - %w[test-evidence security-findings]
evidence_roles = %w[supervisor test-agent test-evidence security-agent security-findings independent-reviewer]
labels = ->(role) { { "app.kubernetes.io/name" => "a2a-agent-runtime", "ai-factory.io/agent-role" => role } }
metadata = ->(name, role = nil) {
  value = { "name" => name, "namespace" => "ai-factory-agents" }
  value["labels"] = labels.call(role) if role
  value
}

documents = [
  {
    "apiVersion" => "v1", "kind" => "Namespace",
    "metadata" => { "name" => "ai-factory-agents", "labels" => {
      "pod-security.kubernetes.io/enforce" => "restricted",
      "pod-security.kubernetes.io/audit" => "restricted",
      "pod-security.kubernetes.io/warn" => "restricted",
      "telemetry.ai-factory.example/enabled" => "true"
    } }
  },
  {
    "apiVersion" => "networking.k8s.io/v1", "kind" => "NetworkPolicy",
    "metadata" => metadata.call("a2a-agents-default-deny"),
    "spec" => { "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => "a2a-agent-runtime" } },
      "policyTypes" => %w[Ingress Egress] }
  },
  {
    "apiVersion" => "networking.k8s.io/v1", "kind" => "NetworkPolicy",
    "metadata" => metadata.call("a2a-agents-required-connectivity"),
    "spec" => {
      "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => "a2a-agent-runtime" } },
      "policyTypes" => %w[Ingress Egress],
      "ingress" => [{ "from" => [{ "namespaceSelector" => { "matchLabels" => {
        "kubernetes.io/metadata.name" => "ai-factory-control"
      } }, "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => "orchestrator" } } }],
        "ports" => [{ "protocol" => "TCP", "port" => 8090 }] }],
      "egress" => [
        { "to" => [{ "namespaceSelector" => { "matchLabels" => { "kubernetes.io/metadata.name" => "kube-system" } },
          "podSelector" => { "matchLabels" => { "k8s-app" => "kube-dns" } } }],
          "ports" => [{ "protocol" => "UDP", "port" => 53 }, { "protocol" => "TCP", "port" => 53 }] },
        { "to" => [{ "namespaceSelector" => { "matchLabels" => {
          "kubernetes.io/metadata.name" => "ai-factory-control"
        } }, "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => "temporal" } } }],
          "ports" => [{ "protocol" => "TCP", "port" => 7233 }] },
        { "to" => [{ "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => "a2a-task-db-proxy" } } }],
          "ports" => [{ "protocol" => "TCP", "port" => 5432 }] },
        { "to" => [{ "namespaceSelector" => { "matchLabels" => {
          "kubernetes.io/metadata.name" => "ai-factory-observability"
        } }, "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => "otel-gateway" } } }],
          "ports" => [{ "protocol" => "TCP", "port" => 4317 }] }
      ]
    }
  }
]

roles.each do |role|
  name = "a2a-#{role}"
  selector = { "matchLabels" => labels.call(role) }
  documents << {
    "apiVersion" => "v1", "kind" => "ServiceAccount", "metadata" => metadata.call(name, role),
    "automountServiceAccountToken" => false
  }
  documents << {
    "apiVersion" => "secrets-store.csi.x-k8s.io/v1", "kind" => "SecretProviderClass",
    "metadata" => metadata.call(name),
    "spec" => { "provider" => "gcp", "parameters" => { "secrets" => [
      "resourceName: projects/PROJECT_ID/secrets/a2a-#{role}-tls-crt/versions/latest\nfileName: tls.crt",
      "resourceName: projects/PROJECT_ID/secrets/a2a-#{role}-tls-key/versions/latest\nfileName: tls.key",
      "resourceName: projects/PROJECT_ID/secrets/a2a-ca-crt/versions/latest\nfileName: ca.crt",
      "resourceName: projects/PROJECT_ID/secrets/a2a-ca-crl/versions/latest\nfileName: ca.crl",
      "resourceName: projects/PROJECT_ID/secrets/a2a-#{role}-card-jwks/versions/latest\nfileName: card-jwks.json",
      "resourceName: projects/PROJECT_ID/secrets/a2a-#{role}-mcp-token/versions/latest\nfileName: mcp-access-token"
    ].join("\n") } }
  }
  documents << {
    "apiVersion" => "apps/v1", "kind" => "Deployment", "metadata" => metadata.call(name, role),
    "spec" => {
      "replicas" => 2, "selector" => selector,
      "strategy" => { "type" => "RollingUpdate", "rollingUpdate" => { "maxUnavailable" => 0, "maxSurge" => 1 } },
      "template" => {
        "metadata" => { "labels" => labels.call(role) },
        "spec" => {
          "serviceAccountName" => name, "automountServiceAccountToken" => false,
          "securityContext" => { "runAsNonRoot" => true, "runAsUser" => 10_001, "runAsGroup" => 10_001,
            "fsGroup" => 10_001, "seccompProfile" => { "type" => "RuntimeDefault" } },
          "topologySpreadConstraints" => [{ "maxSkew" => 1, "topologyKey" => "topology.kubernetes.io/zone",
            "whenUnsatisfiable" => "ScheduleAnyway", "labelSelector" => selector }],
          "containers" => [{
            "name" => "agent-runtime",
            "image" => "REGISTRY/ai-factory-a2a-agent-runtime@sha256:REPLACE_WITH_64_HEX_DIGEST",
            "ports" => [{ "name" => "a2a", "containerPort" => 8090 }],
            "env" => [
              { "name" => "AI_FACTORY_AGENT_ROLE", "value" => role },
              { "name" => "AI_FACTORY_AGENT_ENDPOINT", "value" => "https://#{name}.ai-factory-agents.svc.cluster.local:8090/a2a" },
              { "name" => "AI_FACTORY_A2A_TEMPORAL_ENABLED", "value" => "true" },
              { "name" => "AI_FACTORY_A2A_TASK_STORE_ENABLED", "value" => "true" },
              { "name" => "AI_FACTORY_A2A_TLS_ENABLED", "value" => "true" },
              { "name" => "AI_FACTORY_A2A_MTLS_REQUIRED", "value" => "true" },
              { "name" => "AI_FACTORY_A2A_CARD_ACTIVE_KID", "value" => "a2a-#{role}-gke-v1" },
              { "name" => "AI_FACTORY_A2A_TASK_DB_PASSWORD", "valueFrom" => {
                "secretKeyRef" => { "name" => "a2a-runtime-database", "key" => "password" }
              } }
            ],
            "envFrom" => [{ "configMapRef" => { "name" => "a2a-runtime-config" } }],
            "readinessProbe" => { "exec" => { "command" => ["sh", "-c",
              "curl -fsSk --cert /var/run/ai-factory/a2a/tls.crt --key /var/run/ai-factory/a2a/tls.key https://127.0.0.1:8090/actuator/health/readiness >/dev/null"] },
              "periodSeconds" => 10, "timeoutSeconds" => 3, "failureThreshold" => 6 },
            "livenessProbe" => { "exec" => { "command" => ["sh", "-c",
              "curl -fsSk --cert /var/run/ai-factory/a2a/tls.crt --key /var/run/ai-factory/a2a/tls.key https://127.0.0.1:8090/actuator/health/liveness >/dev/null"] },
              "periodSeconds" => 30, "timeoutSeconds" => 3, "failureThreshold" => 3 },
            "resources" => { "requests" => { "cpu" => "100m", "memory" => "384Mi" },
              "limits" => { "cpu" => "750m", "memory" => "768Mi" } },
            "securityContext" => { "allowPrivilegeEscalation" => false,
              "capabilities" => { "drop" => ["ALL"] }, "readOnlyRootFilesystem" => true },
            "volumeMounts" => [{ "name" => "secrets", "mountPath" => "/var/run/ai-factory/a2a", "readOnly" => true },
              { "name" => "tmp", "mountPath" => "/tmp" }]
          }],
          "volumes" => [{ "name" => "secrets", "csi" => { "driver" => "secrets-store.csi.k8s.io",
            "readOnly" => true, "volumeAttributes" => { "secretProviderClass" => name } } },
            { "name" => "tmp", "emptyDir" => { "sizeLimit" => "64Mi" } }]
        }
      }
    }
  }
  documents << {
    "apiVersion" => "v1", "kind" => "Service", "metadata" => metadata.call(name, role),
    "spec" => { "type" => "ClusterIP", "selector" => labels.call(role),
      "ports" => [{ "name" => "a2a", "port" => 8090, "targetPort" => "a2a" }] }
  }
  documents << {
    "apiVersion" => "policy/v1", "kind" => "PodDisruptionBudget", "metadata" => metadata.call(name, role),
    "spec" => { "minAvailable" => 1, "selector" => selector }
  }
  documents << {
    "apiVersion" => "autoscaling/v2", "kind" => "HorizontalPodAutoscaler", "metadata" => metadata.call(name, role),
    "spec" => { "minReplicas" => 2, "maxReplicas" => 10,
      "scaleTargetRef" => { "apiVersion" => "apps/v1", "kind" => "Deployment", "name" => name },
      "behavior" => { "scaleDown" => { "stabilizationWindowSeconds" => 300 } },
      "metrics" => [{ "type" => "External", "external" => {
        "metric" => { "name" => "temporal_task_queue_backlog", "selector" => { "matchLabels" => { "agent_role" => role } } },
        "target" => { "type" => "AverageValue", "averageValue" => "5" }
      } }]
    }
  }
  mcp_egress = []
  mcp_egress << ["repository-context-mcp", 8091] if context_roles.include?(role)
  mcp_egress << ["evidence-mcp", 8095] if evidence_roles.include?(role)
  next if mcp_egress.empty?
  documents << {
    "apiVersion" => "networking.k8s.io/v1", "kind" => "NetworkPolicy",
    "metadata" => metadata.call("#{name}-mcp", role),
    "spec" => { "podSelector" => selector, "policyTypes" => ["Egress"],
      "egress" => mcp_egress.map { |server, port| { "to" => [{ "namespaceSelector" => { "matchLabels" => {
        "kubernetes.io/metadata.name" => "ai-factory-services"
      } }, "podSelector" => { "matchLabels" => { "app.kubernetes.io/name" => server } } }],
        "ports" => [{ "protocol" => "TCP", "port" => port }] } } }
  }
end

content = documents.map { |document| YAML.dump(document) }.join
if ARGV.include?("--check")
  abort "Generated A2A GKE manifests are stale; run scripts/generate-a2a-gke-manifests.rb" unless
    File.exist?(output) && File.read(output) == content
  puts "Generated A2A GKE manifests are current."
else
  File.write(output, content)
  puts "Generated #{documents.length} A2A GKE resources in #{output}."
end
