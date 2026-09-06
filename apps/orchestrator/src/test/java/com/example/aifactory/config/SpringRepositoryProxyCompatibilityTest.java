package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringRepositoryProxyCompatibilityTest {
    @Test
    void repositoryBeansRemainProxyableForSpringExceptionTranslation() {
        List<Class<?>> repositories = List.of(
                com.example.aifactory.workflow.projection.PostgresProjectionRebuildCatalog.class,
                com.example.aifactory.workflow.projection.PostgresUiProjectionStore.class,
                com.example.aifactory.workflow.projection.PostgresTaskMemory.class,
                com.example.aifactory.workflow.migration.PostgresLegacyTaskMigrationTarget.class,
                com.example.aifactory.service.InMemoryRoutingDecisionJournal.class);

        assertThat(repositories).allSatisfy(repository -> {
            assertThat(repository.isAnnotationPresent(Repository.class)).isTrue();
            assertThat(Modifier.isFinal(repository.getModifiers()))
                    .as("%s must remain proxyable", repository.getName()).isFalse();
        });
    }
}
