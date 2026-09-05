package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts only the fully registered worker factory and drains it before SDK client destruction. */
@Component
public final class TemporalWorkerLifecycle implements SmartLifecycle {
    private static final int REQUIRED_WORKERS = 7;
    private final WorkerFactory factory;
    private final TemporalWorkerRegistry registry;
    private final Duration shutdownTimeout;
    private final AtomicBoolean running = new AtomicBoolean();

    public TemporalWorkerLifecycle(WorkerFactory factory, TemporalWorkerRegistry registry,
                                   TemporalProperties properties) {
        this.factory = factory;
        this.registry = registry;
        this.shutdownTimeout = properties.capacity().gracefulShutdownTimeout();
    }

    @Override
    public void start() {
        if (running.get()) return;
        if (registry.workers().size() != REQUIRED_WORKERS) {
            throw new IllegalStateException("Temporal workers are not completely registered");
        }
        factory.start();
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        try {
            if (running.getAndSet(false) || factory.isStarted()) {
                factory.shutdown();
                factory.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!factory.isTerminated()) factory.shutdownNow();
            }
        } catch (RuntimeException failure) {
            factory.shutdownNow();
            throw failure;
        } finally {
            callback.run();
        }
    }

    @Override public boolean isRunning() { return running.get() && factory.isStarted() && !factory.isShutdown(); }
    @Override public boolean isAutoStartup() { return true; }

    /** Starts after ordinary components and stops before them. */
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
