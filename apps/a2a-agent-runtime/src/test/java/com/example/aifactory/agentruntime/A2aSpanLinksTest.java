package com.example.aifactory.agentruntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class A2aSpanLinksTest {
    @Test
    void createsASeparateTraceLinkedToTheRemoteA2aBoundary() {
        CollectingExporter exporter = new CollectingExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try {
            A2aSpanLinks links = new A2aSpanLinks(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            String sourceTraceId = "0af7651916cd43dd8448eb211c80319c";
            String result = links.call("ai.factory.a2a.agent.workflow", "task-to-agent-workflow",
                    "00-" + sourceTraceId + "-b7ad6b7169203331-01",
                    Map.of("a2a.task.id", "task-1"), () -> "done");

            assertThat(result).isEqualTo("done");
            assertThat(exporter.spans).hasSize(1);
            SpanData span = exporter.spans.getFirst();
            assertThat(span.getParentSpanContext().isValid()).isFalse();
            assertThat(span.getLinks()).singleElement().satisfies(link -> {
                assertThat(link.getSpanContext().getTraceId()).isEqualTo(sourceTraceId);
                assertThat(link.getAttributes().get(AttributeKey.stringKey("a2a.link.relation")))
                        .isEqualTo("task-to-agent-workflow");
            });
        } finally {
            provider.close();
        }
    }

    @Test
    void workflowImplementationContainsNoTelemetrySideEffect() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/aifactory/agentruntime/AgentTaskWorkflowV1Impl.java"));
        assertThat(source).doesNotContain("io.opentelemetry", "Span.current", "A2aSpanLinks");
    }

    private static final class CollectingExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();
        @Override public CompletableResultCode export(Collection<SpanData> values) {
            spans.addAll(values);
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
