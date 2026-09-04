package dev.retrystorm.lab.dlq;

public class DeadLetterConflictException extends RuntimeException {
    public DeadLetterConflictException() {
        super("이미 처리 중이거나 버전이 변경되었습니다.");
    }
}
