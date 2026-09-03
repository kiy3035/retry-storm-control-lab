package dev.retrystorm.lab.api;

import java.util.UUID;

import dev.retrystorm.lab.message.ProcessingState;

public record PublishMessageResponse(
        UUID messageId,
        ProcessingState state) {
}
