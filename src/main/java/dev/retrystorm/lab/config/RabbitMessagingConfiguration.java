package dev.retrystorm.lab.config;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.retrystorm.lab.message.RetryableMessageProcessingException;
import dev.retrystorm.lab.retry.JitterSource;
import dev.retrystorm.lab.retry.RetryBackOffPolicyFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@EnableConfigurationProperties({MessagingProperties.class, RetryProperties.class})
public class RabbitMessagingConfiguration {

    public static final String PARKING_EXCHANGE = "retry.lab.parking";
    public static final String PARKING_QUEUE = "retry.lab.parking.v4";

    @Bean
    DirectExchange workExchange(MessagingProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue workQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.workQueue())
                .deadLetterExchange(PARKING_EXCHANGE)
                .deadLetterRoutingKey(PARKING_QUEUE).build();
    }

    @Bean
    Binding workBinding(@Qualifier("workQueue") Queue workQueue,
            @Qualifier("workExchange") DirectExchange workExchange, MessagingProperties properties) {
        return BindingBuilder.bind(workQueue).to(workExchange).with(properties.routingKey());
    }

    @Bean
    DirectExchange parkingExchange() {
        return new DirectExchange(PARKING_EXCHANGE, true, false);
    }

    @Bean
    Queue parkingQueue() {
        return QueueBuilder.durable(PARKING_QUEUE).build();
    }

    @Bean
    Binding parkingBinding(@Qualifier("parkingQueue") Queue queue,
            @Qualifier("parkingExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(PARKING_QUEUE);
    }

    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    Sleeper retrySleeper() {
        return Thread::sleep;
    }

    @Bean
    JitterSource retryJitterSource() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }

    @Bean
    RetryBackOffPolicyFactory retryBackOffPolicyFactory(JitterSource jitterSource, Sleeper sleeper) {
        return new RetryBackOffPolicyFactory(jitterSource, sleeper);
    }

    @Bean
    BackOffPolicy retryBackOffPolicy(
            RetryBackOffPolicyFactory factory,
            RetryProperties properties) {
        return factory.create(properties);
    }

    @Bean
    RetryTemplate retryTemplate(RetryProperties properties, BackOffPolicy backOffPolicy) {
        var retryPolicy = new SimpleRetryPolicy(
                properties.maxAttempts(),
                Map.of(RetryableMessageProcessingException.class, true),
                true);

        var retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }
}
