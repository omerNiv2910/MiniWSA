package com.miniwsa.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the Kafka topics the service uses. Partition counts are dev defaults.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic rawEventsTopic(MiniWsaProperties props) {
        return TopicBuilder.name(props.topics().raw()).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic(MiniWsaProperties props) {
        return TopicBuilder.name(props.topics().dlq()).partitions(1).replicas(1).build();
    }
}
