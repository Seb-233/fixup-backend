package com.fixup.shared.configuration;

import com.fixup.shared.security.DatabaseActorContext;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes the resolved actor as PostgreSQL session variables (app.current_user_id,
 * app.current_user_roles) at the start of every transaction that has one, and downgrades the
 * connection to the low-privilege fixup_app role for its duration, via SET LOCAL: both self-reset
 * at commit/rollback, so nothing leaks to the next transaction on a pooled connection.
 *
 * <p>This is what lets the FR-UC-25 Row-Level Security policies (V6 migration) actually apply: a
 * superuser or the owner of a table always bypasses RLS in PostgreSQL, so application traffic must
 * never run under those privileges once RLS is meant to hold.
 *
 * <p>A transaction with no actor (Flyway, scheduled jobs, test fixtures set up directly against the
 * database) is left on the original connection unchanged: RLS is a barrier for user-driven requests,
 * not for trusted internal processes. Against any non-PostgreSQL database (the H2 profile used by
 * fast HTTP-contract tests) this is a no-op, since neither the role nor the session variables exist.
 *
 * <p>DatabaseActorContext is only ever populated by CurrentActorProvider.currentActor(), and only
 * ever cleared by the MVC interceptor once a real HTTP request completes -- so it stays correctly
 * scoped to "one request" for every actual code path in this application. Directly invoking a
 * @PreAuthorize-guarded use case outside of any HTTP request (as one identityaccess/fixers test does,
 * on purpose, to prove method security holds even then) is the one exception: nothing ever clears the
 * context afterwards, so tests that do this are responsible for clearing it themselves in a finally
 * block, the same way they already reset SecurityContextHolder.
 */
class RlsSessionTransactionManager extends JpaTransactionManager {
    private static final String ACTIVATE_ACTOR_SQL =
            "SELECT set_config('app.current_user_id', ?, true), set_config('app.current_user_roles', ?, true)";

    RlsSessionTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        var actor = DatabaseActorContext.current();
        if (actor.isEmpty()) {
            return;
        }
        var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder == null) {
            return;
        }
        holder.getEntityManager().unwrap(Session.class)
                .doWork(connection -> activateActor(connection, actor.get()));
    }

    private void activateActor(Connection connection, DatabaseActorContext.Actor actor) throws SQLException {
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
