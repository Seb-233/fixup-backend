package com.fixup.shared.configuration;

import com.fixup.shared.security.DatabaseActorContext;
import com.fixup.shared.security.DatabaseIdentityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.orm.jpa.JpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** Activates actor RLS with transaction-local settings. Identity resolution uses a separate,
 * authenticated identity transaction; its SELECT isolation depends on policies and query filters.
 * Actor-less runtime work receives no additional privileges. */
class RlsSessionTransactionManager extends JpaTransactionManager {
    private static final String ACTIVATE_ACTOR_SQL =
            "SELECT set_config('app.current_user_id', ?, true), set_config('app.current_user_roles', ?, true), "
            + "set_config('app.identity_subject', '', true)";

    RlsSessionTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
        setJpaDialect(super.getJpaDialect());
    }

    // Spring's doBegin invokes the dialect before binding EM/JDBC resources. Activating here
    // avoids a post-begin holder lookup and lets Spring own failed-begin rollback/close.
    @Override
    public void setJpaDialect(JpaDialect dialect) {
        super.setJpaDialect(dialect);
        if (!(super.getJpaDialect() instanceof RlsJpaDialect)) {
            super.setJpaDialect(new RlsJpaDialect(super.getJpaDialect()));
        }
    }

    private static final class RlsJpaDialect implements JpaDialect {
        private final JpaDialect delegate;

        private RlsJpaDialect(JpaDialect delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object beginTransaction(EntityManager em, TransactionDefinition definition)
                throws SQLException {
            Object data = delegate.beginTransaction(em, definition);
            var actor = DatabaseActorContext.current();
            var subject = DatabaseIdentityContext.currentSubject();
            if (actor.isEmpty() && subject.isEmpty()) return data;
            var connection = new AtomicReference<Connection>();
            try {
                em.unwrap(Session.class).doWork(c -> {
                    connection.set(c);
                    if (actor.isPresent()) activateActor(c, actor.get());
                    else activateIdentity(c, subject.orElseThrow());
                });
                return data;
            } catch (RuntimeException | Error failure) {
                // beginTransaction returned dialect state, but Spring has not received it yet.
                // Undo it here; Spring's closeEntityManagerAfterFailedBegin owns EM closure.
                try {
                    em.getTransaction().rollback();
                } catch (RuntimeException | Error rollbackFailure) {
                    suppress(failure, rollbackFailure);
                    // A failed rollback cannot leave a reusable physical connection in the pool.
                    if (connection.get() != null) {
                        try { connection.get().abort(Runnable::run); }
                        catch (SQLException | RuntimeException | Error abortFailure) {
                            suppress(failure, abortFailure);
                        }
                    }
                }
                try { delegate.cleanupTransaction(data); }
                catch (RuntimeException | Error cleanupFailure) { suppress(failure, cleanupFailure); }
                throw failure;
            }
        }

        @Override
        public Object prepareTransaction(EntityManager em, boolean readOnly, String name) {
            return delegate.prepareTransaction(em, readOnly, name);
        }

        @Override public void cleanupTransaction(Object data) { delegate.cleanupTransaction(data); }

        @Override
        public ConnectionHandle getJdbcConnection(
                EntityManager em, boolean readOnly) throws SQLException {
            return delegate.getJdbcConnection(em, readOnly);
        }

        @Override
        public void releaseJdbcConnection(ConnectionHandle connection,
                EntityManager em) throws SQLException {
            delegate.releaseJdbcConnection(connection, em);
        }

        @Override
        public DataAccessException translateExceptionIfPossible(RuntimeException failure) {
            return delegate.translateExceptionIfPossible(failure);
        }
    }

    private static void suppress(Throwable original, Throwable secondary) {
        // Some providers may reuse an exception; self-suppression would hide the original.
        if (original != secondary) original.addSuppressed(secondary);
    }

    private static void activateIdentity(Connection connection, String subject) throws SQLException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
        try (var statement = connection.prepareStatement(
                "SELECT set_config('app.identity_subject', ?, true), "
                + "set_config('app.current_user_id', '', true), set_config('app.current_user_roles', '', true)")) {
            statement.setString(1, subject);
            statement.execute();
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE fixup_identity");
        }
    }

    private static void activateActor(Connection connection, DatabaseActorContext.Actor actor) throws SQLException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        try (var statement = connection.prepareStatement(ACTIVATE_ACTOR_SQL)) {
            statement.setString(1, actor.userId().toString());
            statement.setString(2, String.join(",", actor.roles()));
            statement.execute();
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE fixup_app");
        }
    }
}
