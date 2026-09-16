package com.fixup.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationContextTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldDenyAnonymousRequests() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyAuthenticatedRequestsUntilContractsAreApproved() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(user("architecture-test")))
                .andExpect(status().isForbidden());
    }
}
