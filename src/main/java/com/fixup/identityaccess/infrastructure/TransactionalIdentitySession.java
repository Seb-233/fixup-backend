package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.application.ExternalIdentityProvider;
import com.fixup.identityaccess.application.IdentitySession;
import com.fixup.shared.security.DatabaseActorContext;
import com.fixup.shared.security.DatabaseIdentityContext;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class TransactionalIdentitySession implements IdentitySession {
    private final ExternalIdentityProvider identity;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    TransactionalIdentitySession(ExternalIdentityProvider identity, PlatformTransactionManager manager) {
        this.identity = identity;
        transaction = template(manager, false);
        readOnlyTransaction = template(manager, true);
    }

    private TransactionTemplate template(PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    @Override
    public <T> T execute(String subject, Supplier<T> work) {
        return inSession(subject, transaction, work);
    }

    @Override
    public <T> T read(String subject, Supplier<T> work) {
        return inSession(subject, readOnlyTransaction, work);
    }

    private <T> T inSession(String subject, TransactionTemplate template, Supplier<T> work) {
        if (!identity.currentSubject().equals(subject)) {
            throw new AccessDeniedException("Identity subject mismatch");
        }
        if (DatabaseIdentityContext.currentSubject().filter(current -> !current.equals(subject)).isPresent()) {
            throw new AccessDeniedException("Cannot change identity inside an identity session");
        }
        var previousActor = DatabaseActorContext.current();
        DatabaseActorContext.clear();
        try {
            // Scope exists before TransactionTemplate calls doBegin. No SQL role switching here.
            return DatabaseIdentityContext.withSubject(subject, () -> template.execute(status -> work.get()));
        } finally {
            DatabaseActorContext.clear();
            previousActor.ifPresent(DatabaseActorContext::set);
        }
    }
}
