package com.fixup.shared.configuration;

import com.fixup.shared.security.DatabaseActorContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaDialect;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RlsSessionTransactionManagerTest {
    @AfterEach void clear() {
        DatabaseActorContext.clear();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false", "true,true,true"})
    void failedBeginRetainsOriginalFailureAndSpringClosesResources(boolean rollbackFails, boolean cleanupFails, boolean sameFailure) throws Exception {
        var emf = mock(EntityManagerFactory.class);
        var first = mock(EntityManager.class);
        var second = mock(EntityManager.class);
        when(emf.createEntityManager()).thenReturn(first, second);
        var tx = transaction(first);
        transaction(second);
        var connection = connection();
        var session = mock(Session.class);
        when(first.unwrap(Session.class)).thenReturn(session);
        var original = new IllegalStateException("original role activation");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.hasResource(emf)).isFalse();
            Work work = call.getArgument(0);
            work.execute(connection);
            throw original; // Even failure AFTER SET LOCAL must be recovered.
        }).when(session).doWork(any());
        session(second, connection);
        var rollback = sameFailure ? original : new IllegalStateException("rollback failure");
        if (rollbackFails) doThrow(rollback).doNothing().when(tx).rollback();
        var cleanup = sameFailure ? original : new IllegalStateException("cleanup failure");
        var delegate = dialect();
        if (cleanupFails) doThrow(cleanup).doNothing().when(delegate).cleanupTransaction(any());
        if (sameFailure) doThrow(original).when(connection).abort(any());
        var manager = new RlsSessionTransactionManager(emf);
        manager.setJpaDialect(delegate);
        DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), Set.of()));
        var template = new TransactionTemplate(manager);
        assertThatThrownBy(() -> template.execute(status -> { throw new AssertionError("must not run"); }))
                .hasCauseReference(original);
        if (rollbackFails) {
            if (!sameFailure) assertThat(original.getSuppressed()).contains(rollback);
            verify(connection).abort(any());
        } else verify(connection, never()).abort(any());
        if (cleanupFails && !sameFailure) assertThat(original.getSuppressed()).contains(cleanup);
        assertThat(original.getSuppressed()).doesNotContain(original);
        verify(first, times(1)).close(); // Spring's failed-begin path, not manual manager cleanup.
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        String next = template.execute(status -> "next transaction");
        assertThat(next).isEqualTo("next transaction");
        verify(second).close();
    }

    @Test
    void noBoundHolderStillActivatesUsingSpringsEntityManagerAndMissingSessionFailsClosed() throws Exception {
        var emf = mock(EntityManagerFactory.class);
        var em = mock(EntityManager.class);
        when(emf.createEntityManager()).thenReturn(em);
        var tx = transaction(em);
        when(em.unwrap(Session.class)).thenThrow(new IllegalStateException("session unavailable"));
        var manager = new RlsSessionTransactionManager(emf);
        manager.setJpaDialect(dialect());
        DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), Set.of()));
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(s -> "must not execute"))
                .hasRootCauseMessage("session unavailable");
        verify(tx).rollback();
        verify(em).close();
    }

    @Test
    void externallyBoundEntityManagerRemainsOwnedByCallerAfterFailedBegin() throws Exception {
        var emf = mock(EntityManagerFactory.class);
        var em = mock(EntityManager.class);
        transaction(em);
        when(em.unwrap(Session.class)).thenThrow(new IllegalStateException("activation failure"));
        var manager = new RlsSessionTransactionManager(emf);
        manager.setJpaDialect(dialect());
        var holder = new EntityManagerHolder(em);
        TransactionSynchronizationManager.bindResource(emf, holder);
        DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), Set.of()));
        try {
            assertThatThrownBy(() -> new TransactionTemplate(manager).execute(s -> "must not execute"))
                    .hasRootCauseMessage("activation failure");
            assertThat(TransactionSynchronizationManager.getResource(emf)).isSameAs(holder);
            assertThat(holder.isSynchronizedWithTransaction()).isFalse();
            verify(em, never()).close();
            session(em, connection());
            String next = new TransactionTemplate(manager).execute(s -> "reused caller EM");
            assertThat(next).isEqualTo("reused caller EM");
            verify(em, never()).close();
        } finally { TransactionSynchronizationManager.unbindResource(emf); }
    }

    private EntityTransaction transaction(EntityManager em) {
        var tx = mock(EntityTransaction.class);
        var active = new AtomicBoolean();
        when(em.getTransaction()).thenReturn(tx);
        when(em.isOpen()).thenReturn(true);
        when(tx.isActive()).thenAnswer(call -> active.get());
        doAnswer(call -> { active.set(true); return null; }).when(tx).begin();
        doAnswer(call -> { active.set(false); return null; }).when(tx).rollback();
        doAnswer(call -> { active.set(false); return null; }).when(tx).commit();
        return tx;
    }

    private JpaDialect dialect() throws Exception {
        var dialect = mock(JpaDialect.class);
        when(dialect.beginTransaction(any(), any())).thenAnswer(call -> {
            EntityManager em = call.getArgument(0); em.getTransaction().begin(); return new Object();
        });
        return dialect;
    }

    private Connection connection() throws Exception {
        var c = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(c.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(c.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(c.createStatement()).thenReturn(mock(Statement.class));
        return c;
    }

    private void session(EntityManager em, Connection c) {
        var session = mock(Session.class);
        // doReturn also permits replacing a prior throwing unwrap stub.
        doReturn(session).when(em).unwrap(Session.class);
        doAnswer(call -> { ((Work) call.getArgument(0)).execute(c); return null; }).when(session).doWork(any());
    }
}
