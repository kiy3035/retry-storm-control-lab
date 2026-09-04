package dev.retrystorm.lab.dlq;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.retrystorm.lab.message.RetryMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeadLetterService {

    private final DeadLetterRepository repository;

    public DeadLetterService(DeadLetterRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void store(RetryMessage message, int attempts) {
        repository.insertOnce(message.messageId(), message.payload(),
                message.failuresBeforeSuccess(), message.publishedAt(), Instant.now(), attempts);
    }

    @Transactional(readOnly = true)
    public List<DeadLetterView> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size,
                        Sort.by("failedAt").descending().and(Sort.by("messageId"))))
                .map(DeadLetter::view).getContent();
    }

    @Transactional(readOnly = true)
    public DeadLetterView get(UUID id) {
        return find(id).view();
    }

    @Transactional
    public Claim claim(UUID id, long expectedVersion, Integer overrideFailures) {
        var entry = find(id);
        entry.claim(expectedVersion);
        repository.flush();
        return new Claim(entry.message(overrideFailures), entry.view().version());
    }

    @Transactional
    public DeadLetterView complete(UUID id, long claimedVersion, boolean succeeded, int attempts) {
        var entry = find(id);
        entry.complete(claimedVersion, succeeded, attempts);
        repository.flush();
        return entry.view();
    }

    private DeadLetter find(UUID id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "DLQ 메시지를 찾을 수 없습니다."));
    }

    public record Claim(RetryMessage message, long version) {
    }
}
