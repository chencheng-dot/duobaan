package org.example.duobaan.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 统一 HTTP 客户端，用于调用天气与大模型外部接口。
 * HttpClient 同时暴露为 bean，供大模型流式读取复用。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    @Primary
    public RestClient externalRestClient(HttpClient httpClient, DuobaanProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(props.getLlm().getTimeoutSeconds()));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** 长耗时路径（ASR 等）专用：读超时 180s，不受全局 llm 超时约束 */
    @Bean
    public RestClient mediaRestClient(HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(180));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
