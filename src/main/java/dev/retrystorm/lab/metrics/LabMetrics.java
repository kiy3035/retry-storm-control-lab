package dev.retrystorm.lab.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import dev.retrystorm.lab.config.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class LabMetrics {
    public enum Path { CONSUME, REPLAY }
    public enum Outcome { SUCCEEDED, FAILED, ERROR }
    public enum StoreOutcome { INSERTED, DUPLICATE, ERROR }

    private final MeterRegistry registry;
    private final String mode;

    public LabMetrics(MeterRegistry registry, RetryProperties properties) {
        this.registry = registry;
        this.mode = properties.mode().name();
        for (var path : Path.values()) {
            registry.counter("lab.processing.attempts", "mode", mode, "path", path.name());
            registry.counter("lab.retries", "mode", mode, "path", path.name());
            for (var outcome : Outcome.values()) {
                timer(path, outcome);
            }
        }
        for (var outcome : StoreOutcome.values()) {
            registry.counter("lab.dlq.store", "outcome", outcome.name());
        }
        registry.counter("lab.publish", "outcome", "SENT");
        registry.counter("lab.publish", "outcome", "ERROR");
        registry.counter("lab.dlq.conflicts");
    }

    public void published(boolean sent) {
        registry.counter("lab.publish", "outcome", sent ? "SENT" : "ERROR").increment();
    }

    public void attempt(Path path, int attempt) {
        registry.counter("lab.processing.attempts", "mode", mode, "path", path.name()).increment();
        if (attempt > 1) {
            registry.counter("lab.retries", "mode", mode, "path", path.name()).increment();
        }
    }

    public void completed(Path path, Outcome outcome, long startedNanos) {
        timer(path, outcome).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void deliveryLatency(Outcome outcome, Instant publishedAt) {
        var elapsed = Duration.between(publishedAt, Instant.now());
        Timer.builder("lab.delivery.latency").tags("mode", mode, "outcome", outcome.name())
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5)).register(registry)
                .record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }

    public void stored(StoreOutcome outcome) {
        registry.counter("lab.dlq.store", "outcome", outcome.name()).increment();
    }

    public void conflict() {
        registry.counter("lab.dlq.conflicts").increment();
    }

    private Timer timer(Path path, Outcome outcome) {
        return Timer.builder("lab.processing.duration")
                .tags("mode", mode, "path", path.name(), "outcome", outcome.name())
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5)).register(registry);
    }
}
