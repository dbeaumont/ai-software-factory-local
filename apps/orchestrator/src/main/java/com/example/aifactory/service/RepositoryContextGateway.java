package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Primary
@Service
public class RepositoryContextGateway implements RepositoryContextProvider {
    private static final Logger log = LoggerFactory.getLogger(RepositoryContextGateway.class);
    private static final Pattern FILE = Pattern.compile("(?m)^--- FILE: (.+) ---$");
    private static final Pattern CITATION = Pattern.compile(
            "(?m)^SOURCE: repo://[^/]+/[0-9a-f]{40}/[^#]+#sha256=[0-9a-f]{64}$");

    private final RepositoryContextService direct;
    private final McpContextProvider mcp;
    private final McpFactoryProperties properties;
    private final Counter shadowSuccess;
    private final Counter shadowFailure;
    private final DistributionSummary directChars;
    private final DistributionSummary mcpChars;
    private final DistributionSummary directTokens;
    private final DistributionSummary mcpTokens;
    private final DistributionSummary fileCoverage;
    private final DistributionSummary citationValidity;

    public RepositoryContextGateway(RepositoryContextService direct,
                                    McpContextProvider mcp,
                                    McpFactoryProperties properties,
                                    MeterRegistry metrics) {
        this.direct = direct;
        this.mcp = mcp;
        this.properties = properties;
        this.shadowSuccess = Counter.builder("ai_factory_mcp_context_shadow_runs")
                .tag("outcome", "success").register(metrics);
        this.shadowFailure = Counter.builder("ai_factory_mcp_context_shadow_runs")
                .tag("outcome", "failure").register(metrics);
        this.directChars = DistributionSummary.builder("ai_factory_mcp_context_shadow_chars")
                .tag("source", "direct").register(metrics);
        this.mcpChars = DistributionSummary.builder("ai_factory_mcp_context_shadow_chars")
                .tag("source", "mcp").register(metrics);
        this.directTokens = DistributionSummary.builder("ai_factory_mcp_context_shadow_tokens_estimated")
                .tag("source", "direct").register(metrics);
        this.mcpTokens = DistributionSummary.builder("ai_factory_mcp_context_shadow_tokens_estimated")
                .tag("source", "mcp").register(metrics);
        this.fileCoverage = DistributionSummary.builder("ai_factory_mcp_context_shadow_file_coverage_ratio")
                .register(metrics);
        this.citationValidity = DistributionSummary.builder("ai_factory_mcp_context_shadow_citation_validity_ratio")
                .register(metrics);
    }

    @Override
    public String collect(Path repository, String taskId, String sourceCommit) throws Exception {
        if (properties.repositoryContextMode() == McpFactoryProperties.ContextMode.MCP_ACTIVE) {
            if (!properties.enabled()) {
                throw new IllegalStateException("MCP context mode is active but MCP is disabled");
            }
            return mcp.collect(repository, taskId, sourceCommit);
        }
        String directContext = direct.collect(repository, taskId, sourceCommit);
        if (!properties.enabled() || properties.repositoryContextMode() == McpFactoryProperties.ContextMode.DIRECT) {
            return directContext;
        }
        try {
            String mcpContext = mcp.collect(repository, taskId, sourceCommit);
            recordComparison(directContext, mcpContext);
            shadowSuccess.increment();
            log.info("MCP shadow context task={}: direct_chars={}, mcp_chars={}, equal={}",
                    taskId, directContext.length(), mcpContext.length(), directContext.equals(mcpContext));
        } catch (RuntimeException exception) {
            shadowFailure.increment();
            log.warn("MCP shadow context failed for task={}: {}", taskId, exception.getMessage());
        }
        return directContext;
    }

    private void recordComparison(String directContext, String mcpContext) {
        directChars.record(directContext.length());
        mcpChars.record(mcpContext.length());
        directTokens.record(estimatedTokens(directContext));
        mcpTokens.record(estimatedTokens(mcpContext));
        Set<String> baselineFiles = files(directContext);
        Set<String> candidateFiles = files(mcpContext);
        Set<String> covered = new HashSet<>(candidateFiles);
        covered.retainAll(baselineFiles);
        fileCoverage.record(baselineFiles.isEmpty() ? 1.0 : (double) covered.size() / baselineFiles.size());
        int citations = count(CITATION.matcher(mcpContext));
        citationValidity.record(candidateFiles.isEmpty() ? 1.0
                : Math.min(1.0, (double) citations / candidateFiles.size()));
    }

    private static Set<String> files(String context) {
        Set<String> files = new HashSet<>();
        Matcher matcher = FILE.matcher(context);
        while (matcher.find()) {
            files.add(matcher.group(1));
        }
        return files;
    }

    private static int count(Matcher matcher) {
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static double estimatedTokens(String context) {
        return Math.ceil(context.length() / 4.0);
    }
}
