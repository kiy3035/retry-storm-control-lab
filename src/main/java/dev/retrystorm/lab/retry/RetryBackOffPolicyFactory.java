package dev.retrystorm.lab.retry;

import dev.retrystorm.lab.config.RetryMode;
import dev.retrystorm.lab.config.RetryProperties;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;

public final class RetryBackOffPolicyFactory {

    private final JitterSource jitterSource;
    private final Sleeper sleeper;

    public RetryBackOffPolicyFactory(JitterSource jitterSource, Sleeper sleeper) {
        this.jitterSource = jitterSource;
        this.sleeper = sleeper;
    }

    public BackOffPolicy create(RetryProperties properties) {
        if (properties.mode() == RetryMode.FIXED) {
            var policy = new FixedBackOffPolicy();
            policy.setBackOffPeriod(properties.fixedDelay().toMillis());
            policy.setSleeper(sleeper);
            return policy;
        }

        return new ExponentialJitterBackOffPolicy(
                properties.initialDelay(),
                properties.multiplier(),
                properties.maxDelay(),
                properties.jitterRatio(),
                jitterSource,
                sleeper);
    }
}
