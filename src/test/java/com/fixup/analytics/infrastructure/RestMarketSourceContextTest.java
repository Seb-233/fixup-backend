package com.fixup.analytics.infrastructure;

import com.fixup.integration.TestJwtConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "fixup.analytics.market-source.provider=rest",
        "fixup.analytics.market-source.base-url=http://localhost:8089"
})
@ActiveProfiles("test")
@Import(TestJwtConfiguration.class)
class RestMarketSourceContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void restMarketSourceClientBeanIsCreatedWhenProviderIsRest() {
        assertThat(context.getBean(RestMarketSourceClient.class)).isNotNull();
        assertThat(context.getBean(MarketSourceClient.class)).isInstanceOf(RestMarketSourceClient.class);
    }
}
