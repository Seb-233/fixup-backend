package com.fixup.properties.infrastructure;

import com.fixup.properties.application.GeocodingProvider;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeocodingProperties.class)
class GeocodingConfiguration {

    @Bean("nominatimRestClient")
    RestClient nominatimRestClient(GeocodingProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .build();
    }

    @Bean
    GeocodingProvider geocodingProvider(
            @Qualifier("nominatimRestClient") RestClient restClient,
            GeocodingProperties properties,
            Clock clock) {
        return new NominatimGeocodingProvider(restClient, properties, clock);
    }
}
