package dev.retrystorm.lab.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import dev.retrystorm.lab.config.RetryMode;
import dev.retrystorm.lab.config.RetryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LabMetricsTest {
    @Test
    void countsOnlyAttemptsAfterFirstAsRetriesAndUsesBoundedLabels() {
        var properties = mock(RetryProperties.class);
        when(properties.mode()).thenReturn(RetryMode.EXPONENTIAL_JITTER);
        var registry = new SimpleMeterRegistry();
        var metrics = new LabMetrics(registry, properties);
        metrics.attempt(LabMetrics.Path.CONSUME, 1);
        metrics.attempt(LabMetrics.Path.CONSUME, 2);
        metrics.attempt(LabMetrics.Path.CONSUME, 3);
        metrics.published(false);
        metrics.stored(LabMetrics.StoreOutcome.ERROR);
        assertThat(registry.get("lab.retries").tag("path", "CONSUME").counter().count()).isEqualTo(2);
        assertThat(registry.get("lab.processing.attempts").tag("path", "CONSUME").counter().count()).isEqualTo(3);
        assertThat(registry.get("lab.publish").tag("outcome", "ERROR").counter().count()).isEqualTo(1);
        for (var meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).allSatisfy(tag ->
                    assertThat(tag.getKey()).isIn("mode", "path", "outcome"));
        }
    }

    @Test
    void futurePublisherClockDoesNotProduceNegativeLatency() {
        var properties = mock(RetryProperties.class);
        when(properties.mode()).thenReturn(RetryMode.FIXED);
        var registry = new SimpleMeterRegistry();
        new LabMetrics(registry, properties).deliveryLatency(
                LabMetrics.Outcome.SUCCEEDED, Instant.now().plusSeconds(60));
        var timer = registry.get("lab.delivery.latency").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isZero();
    }
}
