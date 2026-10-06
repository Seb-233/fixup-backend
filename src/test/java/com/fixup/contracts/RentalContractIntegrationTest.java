package com.fixup.contracts;

import com.fixup.contracts.api.ContractExpiringSoon;
import com.fixup.integration.TestJwtConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, RentalContractIntegrationTest.ContractTestConfig.class})
class RentalContractIntegrationTest extends RentalContractHttpContract {

    @Configuration
    static class ContractTestConfig {
        @Bean
        @Primary
        RentalContractHttpContract.TestExpiringEventListener testExpiringEventListener() {
            return new RentalContractHttpContract.TestExpiringEventListener();
        }
    }
}
