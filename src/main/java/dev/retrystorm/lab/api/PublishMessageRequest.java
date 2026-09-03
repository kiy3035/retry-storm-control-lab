package dev.retrystorm.lab.api;

public record PublishMessageRequest(
        String payload,
        Integer failuresBeforeSuccess) {
}
