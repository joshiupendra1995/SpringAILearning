package com.springai.fundamentals.config;

import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class OllamaClientConfiguration {

    @Bean
    OllamaApi ollamaApi(
            ObservationRegistry observationRegistry,
            ObjectProvider<RestClient.Builder> restClientBuilders,
            WebClient.Builder webClientBuilder,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.ollama.response-timeout:5m}") Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create().responseTimeout(responseTimeout);
        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(responseTimeout);

        // Boot does not auto-configure RestClient.Builder in a reactive application.
        // Its fallback still needs the shared registry for HTTP spans and W3C propagation.
        RestClient.Builder restClientBuilder = restClientBuilders.getIfAvailable(RestClient::builder).clone()
                .observationRegistry(observationRegistry)
                .requestFactory(requestFactory);

        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder.clone()
                        .observationRegistry(observationRegistry)
                        .clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();
    }
}
