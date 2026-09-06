package com.example.aifactory.agentruntime;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class A2aTaskStoreConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.task-store.enabled", havingValue = "true")
    HikariDataSource a2aTaskDataSource(A2aTaskStoreProperties properties) {
        if (properties.jdbcUrl() == null || properties.jdbcUrl().isBlank()) {
            throw new IllegalStateException("Durable A2A task store requires jdbc-url");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.jdbcUrl());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(Math.max(1, Math.min(properties.maximumPoolSize(), 16)));
        dataSource.setPoolName("a2a-task-store");
        return dataSource;
    }

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.task-store.enabled", havingValue = "true")
    Flyway a2aTaskFlyway(DataSource a2aTaskDataSource) {
        return Flyway.configure().dataSource(a2aTaskDataSource)
                .locations("classpath:db/a2a-task-migration").baselineOnMigrate(true).load();
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.task-store.enabled", havingValue = "true")
    A2aTaskStore postgresA2aTaskStore(DataSource a2aTaskDataSource, ObjectMapper mapper) {
        JdbcTemplate jdbc = new JdbcTemplate(a2aTaskDataSource);
        return new PostgresA2aTaskStore(jdbc, new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(a2aTaskDataSource)), mapper);
    }

    @Bean
    @ConditionalOnMissingBean(A2aTaskStore.class)
    A2aTaskStore inMemoryA2aTaskStore() { return new InMemoryA2aTaskStore(); }

    @Bean
    A2aAdmissionController a2aAdmissionController(A2aTaskStore store, AgentConcurrencyProperties limits) {
        return new A2aAdmissionController(store, limits);
    }
}
