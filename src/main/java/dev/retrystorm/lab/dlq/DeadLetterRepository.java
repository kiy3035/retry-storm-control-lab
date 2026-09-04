package dev.retrystorm.lab.dlq;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO retry_lab.dead_letters
                (message_id, payload, failures_before_success, published_at, failed_at,
                 failure_code, original_attempts, state, replay_attempts, reprocess_count,
                 updated_at, version)
            VALUES (:id, :payload, :failures, :published, :now,
                    'RETRY_EXHAUSTED', :attempts, 'PENDING', 0, 0, :now, 0)
            ON CONFLICT (message_id) DO NOTHING
            """, nativeQuery = true)
    int insertOnce(@Param("id") UUID id, @Param("payload") String payload,
            @Param("failures") int failures, @Param("published") Instant published,
            @Param("now") Instant now, @Param("attempts") int attempts);
}
