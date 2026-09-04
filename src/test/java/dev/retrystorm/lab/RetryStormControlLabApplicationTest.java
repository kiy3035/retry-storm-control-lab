package dev.retrystorm.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.retrystorm.lab.api.DeadLetterController;
import dev.retrystorm.lab.config.RabbitMessagingConfiguration;
import dev.retrystorm.lab.dlq.DeadLetterRepository;
import dev.retrystorm.lab.dlq.DeadLetterService;
import dev.retrystorm.lab.dlq.DeadLetterState;
import dev.retrystorm.lab.dlq.DeadLetterView;
import dev.retrystorm.lab.message.RetryMessage;
import dev.retrystorm.lab.message.RetryMessageListener;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import dev.retrystorm.lab.api.PublishMessageRequest;
import dev.retrystorm.lab.api.PublishMessageResponse;
import dev.retrystorm.lab.message.ProcessingSnapshot;
import dev.retrystorm.lab.message.ProcessingState;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetryStormControlLabApplicationTest {

    private static final String APP_USER = "retry_app";
    private static final String APP_PASSWORD = "test-" + UUID.randomUUID();
    private static final String RABBIT_USER = "retry_app";
    private static final String RABBIT_PASSWORD = "test-" + UUID.randomUUID();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.9-alpine"))
            .withDatabaseName("retry_storm")
            .withUsername("retry_migrator")
            .withPassword("test-" + UUID.randomUUID());

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.1.4-management-alpine"))
            .withUser(RABBIT_USER, RABBIT_PASSWORD)
            .withPermission("/", RABBIT_USER, ".*", ".*", ".*");

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_db_user", () -> APP_USER);
        registry.add("spring.flyway.placeholders.app_db_password", () -> APP_PASSWORD);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> RABBIT_USER);
        registry.add("spring.rabbitmq.password", () -> RABBIT_PASSWORD);
        registry.add("lab.retry.max-attempts", () -> 3);
        registry.add("lab.retry.mode", () -> "FIXED");
        registry.add("lab.retry.fixed-delay", () -> "75ms");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    ConnectionFactory rabbitConnectionFactory;

    @Autowired
    TestRestTemplate restTemplate;

    @LocalServerPort
    int port;

    @MockitoSpyBean
    DeadLetterService deadLetters;

    @Autowired
    DeadLetterRepository deadLetterRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RetryMessageListener messageListener;

    @Test
    void concurrentInitialInsertsProduceOneDurableRow() throws Exception {
        var id = UUID.randomUUID();
        var message = new RetryMessage(
                id, "동시 저장 합성 메시지", 3, Instant.now());
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> save = () -> {
                gate.await();
                deadLetters.store(message, 3);
                return null;
            };
            var first = pool.submit(save);
            var second = pool.submit(save);
            gate.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM retry_lab.dead_letters WHERE message_id = ?",
                    Integer.class, id)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void redeliveryRetainsPerDeliveryBudgetAndDoesNotDuplicateDlq() {
        var id = UUID.randomUUID();
        var message = new RetryMessage(
                id, "재전달 합성 메시지", 3, Instant.now());
        messageListener.consume(message);
        messageListener.consume(message);
        assertThat(deadLetters.get(id).originalAttempts()).isEqualTo(3);
        assertThat(deadLetters.get(id).version()).isZero();
        assertThat(restTemplate.getForObject("http://localhost:" + port + "/api/v1/messages/" + id,
                ProcessingSnapshot.class).state()).isEqualTo(ProcessingState.FAILED);
    }

    @Test
    void exhaustedMessageIsPersistedOnceAndPayloadIsNotExposed() throws Exception {
        var published = publish("외부 응답에 노출하지 않을 합성 본문", 3);
        awaitTerminalState(published.messageId());
        var entry = deadLetters.get(published.messageId());
        assertThat(entry.state()).isEqualTo(DeadLetterState.PENDING);
        assertThat(entry.originalAttempts()).isEqualTo(3);
        assertThat(entry.failureCode()).isEqualTo("RETRY_EXHAUSTED");

        var duplicate = new RetryMessage(
                published.messageId(), "중복 합성 본문", 3, Instant.now());
        var first = CompletableFuture.runAsync(() -> deadLetters.store(duplicate, 3));
        var second = CompletableFuture.runAsync(() -> deadLetters.store(duplicate, 3));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retry_lab.dead_letters WHERE message_id = ?",
                Integer.class, published.messageId())).isEqualTo(1);
        var response = restTemplate.getForEntity(dlqUrl(published.messageId()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("payload", "외부 응답에");
    }

    @Test
    void replaySucceedsAndStaleVersionIsRejected() throws Exception {
        var id = createDeadLetter();
        var entry = deadLetters.get(id);
        var response = replay(id, entry.version(), 0);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().state()).isEqualTo(DeadLetterState.SUCCEEDED);
        assertThat(response.getBody().replayAttempts()).isEqualTo(1);
        assertThat(response.getBody().reprocessCount()).isEqualTo(1);
        assertThat(replay(id, entry.version(), 0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void failedReplayCanBeRetriedWithNewVersion() throws Exception {
        var id = createDeadLetter();
        var failed = replay(id, deadLetters.get(id).version(), 3).getBody();
        assertThat(failed.state()).isEqualTo(DeadLetterState.FAILED);
        assertThat(failed.replayAttempts()).isEqualTo(3);
        var succeeded = replay(id, failed.version(), 0).getBody();
        assertThat(succeeded.state()).isEqualTo(DeadLetterState.SUCCEEDED);
        assertThat(succeeded.reprocessCount()).isEqualTo(2);
    }

    @Test
    void concurrentReplayRequestsHaveOnlyOneWinner() throws Exception {
        var id = createDeadLetter();
        long version = deadLetters.get(id).version();
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpStatusCode> request = () -> {
                gate.await();
                return replay(id, version, 2).getStatusCode();
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(deadLetters.get(id).reprocessCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void jpaVersionRejectsSimultaneousUpdatesAfterBothReadTheSameVersion() throws Exception {
        var id = createDeadLetter();
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> update = () -> {
                try {
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                var entry = deadLetterRepository.findById(id).orElseThrow();
                                try {
                                    barrier.await(10, TimeUnit.SECONDS);
                                } catch (Exception exception) {
                                    throw new IllegalStateException("동시성 검증 동기화 실패", exception);
                                }
                                entry.claim(entry.view().version());
                                deadLetterRepository.flush();
                            });
                    return true;
                } catch (OptimisticLockingFailureException exception) {
                    return false;
                }
            };
            var first = pool.submit(update);
            var second = pool.submit(update);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(deadLetters.get(id).version()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void databaseStoreFailureRoutesMessageToParkingQueue() throws Exception {
        Mockito.doThrow(new IllegalStateException("합성 DB 저장 실패"))
                .when(deadLetters).store(ArgumentMatchers.argThat(
                        message -> message.payload().equals("격리 경로 검증")),
                        ArgumentMatchers.anyInt());
        var published = publish("격리 경로 검증", 3);
        var raw = rabbitTemplate.receive(
                RabbitMessagingConfiguration.PARKING_QUEUE, 10_000);
        assertThat(raw).isNotNull();
        var parked = objectMapper.readValue(raw.getBody(), RetryMessage.class);
        assertThat(parked.messageId()).isEqualTo(published.messageId());
        assertThat(deadLetterRepository.existsById(published.messageId())).isFalse();
        assertThat(restTemplate.getForObject("http://localhost:" + port + "/api/v1/messages/"
                + published.messageId(), ProcessingSnapshot.class).state())
                .isEqualTo(ProcessingState.PERSISTENCE_FAILED);
    }

    @Test
    void dlqApiValidatesInputAndHandlesMissingIds() {
        assertThat(restTemplate.getForEntity(dlqUrl(UUID.randomUUID()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/dlq?size=101", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.postForEntity(dlqUrl(UUID.randomUUID()) + "/reprocess",
                Map.of("failuresBeforeSuccess", 0), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID createDeadLetter() {
        var id = UUID.randomUUID();
        deadLetters.store(new RetryMessage(
                id, "재처리 합성 메시지", 3, Instant.now()), 3);
        return id;
    }

    private String dlqUrl(UUID id) {
        return "http://localhost:" + port + "/api/v1/dlq/" + id;
    }

    private ResponseEntity<DeadLetterView> replay(
            UUID id, long version, int failures) {
        return restTemplate.postForEntity(dlqUrl(id) + "/reprocess",
                new DeadLetterController.ReprocessRequest(version, failures),
                DeadLetterView.class);
    }

    @Test
    void flywayCreatesDeterministicBaselineAndRuntimeRoleHasNoDdlPrivilege() throws Exception {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata_value FROM retry_lab.lab_metadata WHERE metadata_key = 'schema-purpose'",
                String.class))
                .isEqualTo("Synthetic local retry-storm experiment data only");

        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getUserName()).isEqualTo(APP_USER);
        }

        assertThatThrownBy(() -> executeAsRuntimeUser("CREATE TABLE retry_lab.must_not_exist(id INTEGER)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void postgresRabbitMqAndHealthEndpointAreUp() {
        var rabbitConnection = rabbitConnectionFactory.createConnection();
        try {
            assertThat(rabbitConnection.isOpen()).isTrue();
        } finally {
            rabbitConnection.close();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> health = restTemplate.getForObject(
                "http://localhost:" + port + "/actuator/health", Map.class);

        assertThat(health).containsEntry("status", "UP");
        assertThat(health).containsKey("components");
    }

    @Test
    void messageSucceedsOnFirstAttempt() throws Exception {
        var published = publish("즉시 성공", 0);
        var completed = awaitTerminalState(published.messageId());

        assertThat(completed.state()).isEqualTo(ProcessingState.SUCCEEDED);
        assertThat(completed.attemptCount()).isEqualTo(1);
        assertThat(completed.attemptTimestamps()).hasSize(1);
    }

    @Test
    void messageSucceedsOnThirdAttemptWithFixedDelay() throws Exception {
        var published = publish("두 번 실패 후 성공", 2);
        var completed = awaitTerminalState(published.messageId());

        assertThat(completed.state()).isEqualTo(ProcessingState.SUCCEEDED);
        assertThat(completed.attemptCount()).isEqualTo(3);
        assertThat(completed.attemptTimestamps()).hasSize(3);

        for (int index = 1; index < completed.attemptTimestamps().size(); index++) {
            var interval = Duration.between(
                    completed.attemptTimestamps().get(index - 1),
                    completed.attemptTimestamps().get(index));
            assertThat(interval).isGreaterThanOrEqualTo(Duration.ofMillis(60));
        }
    }

    @Test
    void messageFailsAfterExactlyThreeAttempts() throws Exception {
        var published = publish("계속 실패", 3);
        var completed = awaitTerminalState(published.messageId());

        assertThat(completed.state()).isEqualTo(ProcessingState.FAILED);
        assertThat(completed.attemptCount()).isEqualTo(3);
        assertThat(completed.attemptTimestamps()).hasSize(3);
    }

    private PublishMessageResponse publish(String payload, int failuresBeforeSuccess) {
        var response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/messages",
                new PublishMessageRequest(payload, failuresBeforeSuccess),
                PublishMessageResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ProcessingSnapshot awaitTerminalState(UUID messageId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var response = restTemplate.getForEntity(
                    "http://localhost:" + port + "/api/v1/messages/" + messageId,
                    ProcessingSnapshot.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var snapshot = response.getBody();
                if (snapshot.state() == ProcessingState.SUCCEEDED
                        || snapshot.state() == ProcessingState.FAILED) {
                    return snapshot;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("메시지가 제한 시간 안에 종료 상태에 도달하지 못했습니다.");
    }

    private static void executeAsRuntimeUser(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
