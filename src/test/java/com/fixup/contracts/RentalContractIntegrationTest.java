package com.fixup.contracts;

import com.fixup.integration.TestJwtConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, RentalContractIntegrationTest.ContractTestConfig.class})
class RentalContractIntegrationTest extends RentalContractHttpContract {

    @TestConfiguration(proxyBeanMethods = false)
    static class ContractTestConfig {
        @Bean
        @Primary
        RentalContractHttpContract.TestExpiringEventListener testExpiringEventListener() {
            return new RentalContractHttpContract.TestExpiringEventListener();
        }
    }
}
