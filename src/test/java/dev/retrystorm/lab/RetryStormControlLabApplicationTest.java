package dev.retrystorm.lab;

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
