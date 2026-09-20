package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.FreshnessWindow;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketSourceProperties.class)
class AnalyticsConfiguration {

    @Bean
    FreshnessWindow marketIndicatorsFreshnessWindow(MarketSourceProperties properties) {
        return new FreshnessWindow(properties.getFreshness());
    }
}
