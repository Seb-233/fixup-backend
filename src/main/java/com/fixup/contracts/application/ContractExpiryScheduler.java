package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractExpired;
import com.fixup.contracts.api.ContractExpiringSoon;
import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class ContractExpiryScheduler {
    private final RentalContracts contracts;
    private final ApplicationEventPublisher eventPublisher;

    public ContractExpiryScheduler(RentalContracts contracts, ApplicationEventPublisher eventPublisher) {
        this.contracts = contracts;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void runDailyExpiryCheck() {
        processDay(LocalDate.now());
    }

    @Transactional
    public void processDay(LocalDate today) {
        List<RentalContract> activeContracts = contracts.findActiveEndingBetween(
            today.minusYears(1), today.plusDays(30)
        );

        for (RentalContract contract : activeContracts) {
            if (contract.status() != ContractStatus.ACTIVE && contract.status() != ContractStatus.RENEWED) {
                continue;
            }

            long daysUntilEnd = ChronoUnit.DAYS.between(today, contract.endDate());

            if (contract.endDate().isBefore(today) || daysUntilEnd < 0) {
                boolean alreadyReminded = contract.reminderExpiredSent();
                contract.markExpired();
                if (!alreadyReminded) {
                    contract.markReminderExpiredSent();
                }
                contracts.save(contract);
                if (!alreadyReminded) {
                    eventPublisher.publishEvent(new ContractExpired(
                        contract.id(), contract.propertyId(),
                        contract.ownerUserId(), contract.tenantUserId()
                    ));
                }
                continue;
            }

            if (daysUntilEnd <= 30 && daysUntilEnd >= 29 && !contract.reminder30dSent()) {
                contract.markReminder30dSent();
                contracts.save(contract);
                eventPublisher.publishEvent(new ContractExpiringSoon(
                    "30D", contract.id(),
                    contract.ownerUserId(), contract.tenantUserId()
                ));
            }

            if (daysUntilEnd <= 7 && daysUntilEnd >= 6 && !contract.reminder7dSent()) {
                contract.markReminder7dSent();
                contracts.save(contract);
                eventPublisher.publishEvent(new ContractExpiringSoon(
                    "7D", contract.id(),
                    contract.ownerUserId(), contract.tenantUserId()
                ));
            }
        }
    }
}
