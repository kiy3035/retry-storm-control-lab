package dev.retrystorm.lab.api;

import java.util.Map;
import java.util.UUID;

import dev.retrystorm.lab.message.MessageProcessingTracker;
import dev.retrystorm.lab.message.ProcessingSnapshot;
import dev.retrystorm.lab.message.RetryMessagePublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    private final RetryMessagePublisher publisher;
    private final MessageProcessingTracker tracker;

    public MessageController(RetryMessagePublisher publisher, MessageProcessingTracker tracker) {
        this.publisher = publisher;
        this.tracker = tracker;
    }

    @PostMapping
    public ResponseEntity<PublishMessageResponse> publish(@RequestBody PublishMessageRequest request) {
        if (request == null || request.payload() == null || request.payload().isBlank()) {
            throw new IllegalArgumentException("payload는 비어 있을 수 없습니다.");
        }
        int failuresBeforeSuccess = request.failuresBeforeSuccess() == null
                ? 0
                : request.failuresBeforeSuccess();
        if (failuresBeforeSuccess < 0) {
            throw new IllegalArgumentException("failuresBeforeSuccess는 0 이상이어야 합니다.");
        }

        var snapshot = publisher.publish(request.payload(), failuresBeforeSuccess);
        return ResponseEntity.accepted().body(
                new PublishMessageResponse(snapshot.messageId(), snapshot.state()));
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<ProcessingSnapshot> status(@PathVariable UUID messageId) {
        return ResponseEntity.of(tracker.find(messageId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
