package dev.retrystorm.lab.dlq;

public enum DeadLetterState {
    PENDING, PROCESSING, SUCCEEDED, FAILED
}
