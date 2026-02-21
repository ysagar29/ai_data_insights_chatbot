package com.yash.chatbot_rag.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel;
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.time.Duration;

@Configuration
public class BedrockConfig {

    @Value("${spring.ai.bedrock.aws.region:us-east-1}")
    private String region;

    @Value("${spring.ai.bedrock.aws.access-key:}")
    private String accessKey;

    @Value("${spring.ai.bedrock.aws.secret-key:}")
    private String secretKey;

    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.NOOP;
    }

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        // If access key and secret key are provided, use them
        if (accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
            );
        }
        // Otherwise, use the default credentials provider chain
        // This will check: environment variables, system properties, AWS credentials file, IAM role, etc.
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    public TitanEmbeddingBedrockApi titanEmbeddingBedrockApi(AwsCredentialsProvider credentialsProvider) {
        return new TitanEmbeddingBedrockApi(
                "amazon.titan-embed-text-v1",
                credentialsProvider,
                region,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Duration.ofMinutes(2)
        );
    }

    @Bean
    public BedrockTitanEmbeddingModel bedrockTitanEmbeddingModel(
            TitanEmbeddingBedrockApi titanEmbeddingBedrockApi,
            ObservationRegistry observationRegistry) {
        return new BedrockTitanEmbeddingModel(titanEmbeddingBedrockApi, observationRegistry);
    }
}
