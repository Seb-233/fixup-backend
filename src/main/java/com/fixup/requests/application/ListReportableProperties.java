package com.fixup.requests.application;

import com.fixup.contracts.api.TenancyDirectory;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.properties.api.PropertyNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-08: el formulario de nueva solicitud ofrece solo los inmuebles sobre los que el usuario
 * puede reportar fallas: los que arrienda con contrato vigente. Los que posee o administra los
 * lista properties; aquí se suman los arrendados para que el arrendatario no necesite otra pantalla.
 */
@Service
public class ListReportableProperties {
    private final TenancyDirectory tenancy;
    private final PropertyDirectory properties;
    private final Clock clock;

    ListReportableProperties(TenancyDirectory tenancy, PropertyDirectory properties, Clock clock) {
        this.tenancy = tenancy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReportableProperty> rented(CurrentActor actor) {
        RequestAccess.requireActiveRequester(actor);
        if (!actor.hasRole(Role.TENANT)) {
            return List.of();
        }
        var today = LocalDate.now(clock.withZone(CreateRepairRequest.COLOMBIA));
        var result = new ArrayList<ReportableProperty>();
        for (UUID propertyId : tenancy.activePropertyIds(actor.internalUserId(), today)) {
            try {
                var property = properties.require(propertyId);
                result.add(new ReportableProperty(property.id(), property.name(), property.address(), property.city()));
            } catch (PropertyNotFoundException notVisible) {
                // The contract outlived the property row the tenant can see; nothing to report against.
            }
        }
        return result;
    }

    public record ReportableProperty(UUID propertyId, String name, String address, String city) {
    }
}
