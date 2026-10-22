package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.domain.RepairPayments;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-05: los pagos que el propietario debe o ya hizo, y los de los inmuebles que una inmobiliaria
 * administra. Es la forma de volver a un pago pendiente después de salir de la pasarela.
 */
@Service
public class ListOwnPayments {
    private final RepairPayments payments;

    ListOwnPayments(RepairPayments payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public List<PaymentDetail> execute(CurrentActor actor) {
        PaymentAccess.requireActivePayer(actor);
        return payments.findByPayer(actor.internalUserId()).stream().map(PaymentDetail::from).toList();
    }
}
