package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.application.SimulateRepairPayment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentSimulationProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CurrentActorProvider.class, () -> mock(CurrentActorProvider.class))
            .withBean(SimulateRepairPayment.class, () -> mock(SimulateRepairPayment.class))
            .withUserConfiguration(TestPaymentSimulationController.class);
    @Test void outcomeSelectionControllerExistsOnlyInTheTestProfile() {
        runner.withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThat(context).doesNotHaveBean(TestPaymentSimulationController.class));
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).hasSingleBean(TestPaymentSimulationController.class));
    }
}
