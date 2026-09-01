package dev.retrystorm.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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

    private static void executeAsRuntimeUser(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
