package dev.retrystorm.lab.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.retrystorm.lab.config.RetryMode;
import dev.retrystorm.lab.config.RetryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;

class ExponentialJitterBackOffPolicyTest {

    @Test
    void growsExponentiallyAndStopsAtMaximumDelay() {
        var policy = policy(() -> 0.5, ignored -> {
        });

        assertThat(policy.calculateDelayMillis(1, 0.5)).isEqualTo(200);
        assertThat(policy.calculateDelayMillis(2, 0.5)).isEqualTo(400);
        assertThat(policy.calculateDelayMillis(3, 0.5)).isEqualTo(800);
        assertThat(policy.calculateDelayMillis(10, 0.5)).isEqualTo(5_000);
    }

    @Test
    void appliesControlledJitterAtBothBoundaries() {
        var policy = policy(() -> 0.5, ignored -> {
        });

        assertThat(policy.calculateDelayMillis(1, 0.0)).isEqualTo(100);
        assertThat(policy.calculateDelayMillis(1, Math.nextDown(1.0))).isEqualTo(300);
        assertThat(policy.calculateDelayMillis(2, 0.0)).isEqualTo(200);
        assertThat(policy.calculateDelayMillis(2, Math.nextDown(1.0))).isEqualTo(600);
    }

    @Test
    void rejectsRandomSamplesOutsideTheContract() {
        var policy = policy(() -> 1.0, ignored -> {
        });

        assertThatThrownBy(() -> policy.backOff(policy.start(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 이상 1 미만");
    }

    @Test
    void factorySelectsFixedOrJitterPolicyFromConfiguration() throws Exception {
        var delays = new ArrayList<Long>();
        Sleeper sleeper = delays::add;
        var factory = new RetryBackOffPolicyFactory(() -> 0.5, sleeper);

        var fixed = factory.create(properties(RetryMode.FIXED));
        fixed.backOff(fixed.start(null));

        assertThat(fixed).isInstanceOf(FixedBackOffPolicy.class);
        assertThat(delays).containsExactly(75L);
        assertThat(factory.create(properties(RetryMode.EXPONENTIAL_JITTER)))
                .isInstanceOf(ExponentialJitterBackOffPolicy.class)
                .isNotInstanceOf(ExponentialBackOffPolicy.class);
    }

    @Test
    void spreadsSimultaneousRetryDelaysWithoutSleeping() throws Exception {
        var delays = new ConcurrentLinkedQueue<Long>();
        Sleeper sleeper = delays::add;
        var startGate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<?>>();

        try {
            for (int index = 0; index < 100; index++) {
                int sampleIndex = index;
                futures.add(executor.submit(() -> {
                    startGate.await();
                    JitterSource jitterSource = () -> (sampleIndex + 0.5) / 100.0;
                    var policy = policy(jitterSource, sleeper);
                    policy.backOff(policy.start(null));
                    return null;
                }));
            }
            startGate.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(delays).hasSize(100);
        assertThat(delays).allSatisfy(delay -> assertThat(delay).isBetween(100L, 300L));
        assertThat(delays.stream().distinct().count()).isEqualTo(100);
        assertThat(delays).anySatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(110));
        assertThat(delays).anySatisfy(delay -> assertThat(delay).isGreaterThanOrEqualTo(290));
    }

    private static ExponentialJitterBackOffPolicy policy(
            JitterSource jitterSource,
            Sleeper sleeper) {
        return new ExponentialJitterBackOffPolicy(
                Duration.ofMillis(200),
                2.0,
                Duration.ofSeconds(5),
                0.5,
                jitterSource,
                sleeper);
    }

    private static RetryProperties properties(RetryMode mode) {
        return new RetryProperties(
                3,
                mode,
                Duration.ofMillis(75),
                Duration.ofMillis(200),
                2.0,
                Duration.ofSeconds(5),
                0.5);
    }
}
