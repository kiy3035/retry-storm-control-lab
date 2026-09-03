package dev.retrystorm.lab.message;

public class RetryableMessageProcessingException extends RuntimeException {

    public RetryableMessageProcessingException(String message) {
        super(message);
    }
}
