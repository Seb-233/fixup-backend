package com.fixup.payments.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaOwnerDebtsPropertyCheckTest {
    private final OwnerDebtJpaRepository repository = mock(OwnerDebtJpaRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query query = mock(Query.class);
    private final JpaOwnerDebts debts = new JpaOwnerDebts(repository, entityManager);
    private final UUID property = UUID.randomUUID(), owner = UUID.randomUUID();

    private void query() {
        when(entityManager.createNativeQuery("SELECT public.app_property_owner_has_outstanding_debt(:propertyId, :ownerUserId)"))
                .thenReturn(query);
        when(query.setParameter("propertyId", property)).thenReturn(query);
        when(query.setParameter("ownerUserId", owner)).thenReturn(query);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void usesBoundPropertyAndOwnerAndReturnsOnlyBoolean(boolean result) {
        query();
        when(query.getSingleResult()).thenReturn(result);
        assertThat(debts.hasOutstandingDebtForProperty(property, owner)).isEqualTo(result);
        verify(query).setParameter("propertyId", property);
        verify(query).setParameter("ownerUserId", owner);
        verifyNoInteractions(repository);
    }

    @Test void nullIsIndeterminateAndNeverNoDebt() {
        query();
        when(query.getSingleResult()).thenReturn(null);
        assertThatThrownBy(() -> debts.hasOutstandingDebtForProperty(property, owner)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test void unavailableFunctionDoesNotFallBackToFilteredRepository() {
        query();
        var failure = new IllegalStateException("restricted check unavailable");
        when(query.getSingleResult()).thenThrow(failure);
        assertThatThrownBy(() -> debts.hasOutstandingDebtForProperty(property, owner)).isSameAs(failure);
        verifyNoInteractions(repository);
    }

    @Test void requiresTheExistingActorTransaction() throws Exception {
        var annotation = JpaOwnerDebts.class.getMethod("hasOutstandingDebtForProperty", UUID.class, UUID.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
