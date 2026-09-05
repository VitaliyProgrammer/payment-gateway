package com.payflow.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WebhookClientConfig {

    @Bean
    public RestClient webhookRestClient() {
        // Без baseUrl - адреса вебхука своя для кожного мерчанта, тож URL
        // передається повністю при кожному виклику, а не фіксується тут.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(5_000);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
