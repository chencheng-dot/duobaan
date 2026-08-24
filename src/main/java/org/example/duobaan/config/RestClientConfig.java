package org.example.duobaan.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 统一 HTTP 客户端，用于调用天气与大模型外部接口。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient externalRestClient(DuobaanProperties props) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(props.getLlm().getTimeoutSeconds()));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
