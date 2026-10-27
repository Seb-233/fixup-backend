package com.fixup.payments.web;

import com.fixup.payments.api.PaymentConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExpenseLedgerContextHttpTest {
    @Test
    void incompleteHistoricalContextHasExplicitConflictResponse() throws Exception {
        MockMvcBuilders.standaloneSetup(new IncompleteReport())
                .setControllerAdvice(new PaymentErrorHandler()).build()
                .perform(get("/report"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_CONTEXT_INCOMPLETE"));
    }

    @RestController
    static class IncompleteReport {
        @GetMapping("/report")
        String report() {
            throw new PaymentConflictException("EXPENSE_CONTEXT_INCOMPLETE", "Historical expense requires reconciliation");
        }
    }
}
