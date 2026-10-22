package com.fixup.properties.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.util.UUID;

/**
 * FR-UC-26: una remodelación pre-arriendo termina publicando el inmueble en arriendo con el canon
 * que el propietario se fijó como meta. La decisión la toma el propietario en su propia petición.
 */
public interface PropertyListingPublisher {
    PropertySnapshot publishForRent(CurrentActor actor, UUID propertyId, long monthlyRent);
}
