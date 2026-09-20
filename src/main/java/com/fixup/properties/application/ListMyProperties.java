package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListMyProperties {
    private final Properties properties;

    public ListMyProperties(Properties properties) {
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<PropertySummary> execute(CurrentActor actor, String roleFilter, PropertyStatus status,
            UUID managerFilter, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        List<Property> results;
        if ("MANAGER".equalsIgnoreCase(roleFilter)) {
            results = properties.findByOwnerOrManager(null, actor.internalUserId(), status, managerFilter, safeLimit);
        } else if ("OWNER".equalsIgnoreCase(roleFilter)) {
            results = properties.findByOwnerOrManager(actor.internalUserId(), null, status, managerFilter, safeLimit);
        } else {
            results = properties.findByOwnerOrManager(actor.internalUserId(), actor.internalUserId(), status, managerFilter, safeLimit);
        }
        return results.stream().filter(p -> p.status() != com.fixup.properties.api.PropertyStatus.DELETED)
                .map(PropertySummary::of).toList();
    }
}
