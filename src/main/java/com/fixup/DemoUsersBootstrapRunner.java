package com.fixup;

import com.fixup.identityaccess.api.DemoUserBootstrap;
import com.fixup.notifications.api.DemoNotifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("demo")
@Order(Ordered.LOWEST_PRECEDENCE)
final class DemoUsersBootstrapRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoUsersBootstrapRunner.class);
    private final DemoUserBootstrap users;
    private final DemoNotifications notifications;
    private final PlatformTransactionManager txManager;

    DemoUsersBootstrapRunner(DemoUserBootstrap users, DemoNotifications notifications,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.notifications = notifications;
        this.txManager = txManager;
    }

    @Override
    public void run(String... args) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            var result = users.bootstrap();
            if (result.ownerUserId() != null) {
                notifications.seedForOwner(result.ownerUserId());
            }
            log.info("Demo user bootstrap complete: {} created. Super-user ({} roles) sub={}.",
                    result.created(), result.granted(), result.superUserSubject());
        });
    }
}
