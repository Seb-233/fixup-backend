package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.domain.GeocodingCandidate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReverseGeocodeAddressTest {
    private final GeocodingProvider provider = mock(GeocodingProvider.class);
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final ReverseGeocodeAddress useCase = new ReverseGeocodeAddress(provider, actors);

    @Test
    void validatesCoordinatesAndReturnsTheProviderAddress() {
        when(actors.currentActor()).thenReturn(new CurrentActor(UUID.randomUUID(),
                "auth0|owner", Set.of(Role.OWNER), UserStatus.ACTIVE));
        var lat = new BigDecimal("4.657");
        var lon = new BigDecimal("-74.055");
        var candidate = new GeocodingCandidate("Carrera 7, Bogota", lat, lon);
        when(provider.reverseGeocode(lat, lon)).thenReturn(List.of(candidate));

        assertThat(useCase.execute(lat, lon)).containsExactly(candidate);
        assertThatThrownBy(() -> useCase.execute(new BigDecimal("91"), lon))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(lat, new BigDecimal("-181")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
