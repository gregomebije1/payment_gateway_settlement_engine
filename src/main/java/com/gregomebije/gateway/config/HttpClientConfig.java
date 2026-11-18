package com.gregomebije.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;


@Configuration
public class HttpClientConfig {

    @Bean("ledgerRestClient")
    public RestClient ledgerRestClient(
            RestClient.Builder builder,
            JdkClientHttpRequestFactory requestFactory,
            @Value("${services.ledger.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean("walletRestClient")
    public RestClient walletRestClient(
            RestClient.Builder builder,
            JdkClientHttpRequestFactory requestFactory,
            @Value("${services.wallet.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public JdkClientHttpRequestFactory jdkClientHttpRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
