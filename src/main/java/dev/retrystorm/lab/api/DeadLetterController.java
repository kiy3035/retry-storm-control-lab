package dev.retrystorm.lab.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.retrystorm.lab.dlq.DeadLetterConflictException;
import dev.retrystorm.lab.dlq.DeadLetterReprocessor;
import dev.retrystorm.lab.dlq.DeadLetterService;
import dev.retrystorm.lab.dlq.DeadLetterView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dlq")
public class DeadLetterController {

    private final DeadLetterService service;
    private final DeadLetterReprocessor reprocessor;

    public DeadLetterController(DeadLetterService service, DeadLetterReprocessor reprocessor) {
        this.service = service;
        this.reprocessor = reprocessor;
    }

    @GetMapping
    public List<DeadLetterView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page는 0 이상, size는 1~100이어야 합니다.");
        }
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public DeadLetterView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/reprocess")
    public DeadLetterView reprocess(@PathVariable UUID id, @RequestBody ReprocessRequest request) {
        if (request.expectedVersion() == null || request.expectedVersion() < 0
                || (request.failuresBeforeSuccess() != null && request.failuresBeforeSuccess() < 0)) {
            throw new IllegalArgumentException("버전과 합성 실패 횟수는 0 이상이어야 합니다.");
        }
        return reprocessor.reprocess(id, request.expectedVersion(), request.failuresBeforeSuccess());
    }

    @ExceptionHandler({DeadLetterConflictException.class, OptimisticLockingFailureException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException exception) {
        return ResponseEntity.status(409).body(Map.of("code", "DLQ_CONFLICT"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }

    public record ReprocessRequest(Long expectedVersion, Integer failuresBeforeSuccess) {
    }
}
