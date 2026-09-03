package dev.retrystorm.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.messaging")
public record MessagingProperties(
        String exchange,
        String workQueue,
        String routingKey) {

    public MessagingProperties {
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalArgumentException("exchange는 비어 있을 수 없습니다.");
        }
        if (workQueue == null || workQueue.isBlank()) {
            throw new IllegalArgumentException("workQueue는 비어 있을 수 없습니다.");
        }
        if (routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("routingKey는 비어 있을 수 없습니다.");
        }
    }
}
