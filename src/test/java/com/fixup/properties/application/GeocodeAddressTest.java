package com.fixup.properties.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.domain.GeocodingCandidate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GeocodeAddressTest {
    private final GeocodingProvider provider = mock(GeocodingProvider.class);
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final GeocodeAddress geocode = new GeocodeAddress(provider, actors);

    @Test
    void normalizesAValidQueryBeforeCallingTheProvider() {
        when(actors.currentActor()).thenReturn(active(Role.OWNER));
        var candidate = new GeocodingCandidate(
                "Carrera 7 # 72-41, Bogotá, Colombia",
                new BigDecimal("4.657"),
                new BigDecimal("-74.055"));
        when(provider.geocode("Carrera 7 # 72-41")).thenReturn(List.of(candidate));

        var result = geocode.execute("  Carrera   7  # 72-41  ");

        assertThat(result).containsExactly(candidate);
        verify(provider).geocode("Carrera 7 # 72-41");
    }

    @Test
    void rejectsBlankAndOversizedQueriesWithoutCallingTheProvider() {
        when(actors.currentActor()).thenReturn(active(Role.OWNER));

        assertThatThrownBy(() -> geocode.execute("  \t\n  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> geocode.execute("a".repeat(GeocodeAddress.MAX_QUERY_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
        verify(provider, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "TENANT", "PLATFORM_ADMIN"})
    void permitsExpectedRoles(Role role) {
        when(actors.currentActor()).thenReturn(active(role));
        when(provider.geocode("Bogotá")).thenReturn(List.of());

        assertThat(geocode.execute("Bogotá")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"FIXER", "REAL_ESTATE_MANAGER"})
    void deniesRolesOutsideTheUseCase(Role role) {
        when(actors.currentActor()).thenReturn(active(role));

        assertThatThrownBy(() -> geocode.execute("Bogotá"))
                .isInstanceOf(PropertyAccessDeniedException.class);
        verify(provider, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deniesInactiveActors() {
        when(actors.currentActor()).thenReturn(new CurrentActor(
                UUID.randomUUID(), "auth0|inactive", Set.of(Role.OWNER), UserStatus.SUSPENDED));

        assertThatThrownBy(() -> geocode.execute("Bogotá"))
                .isInstanceOf(PropertyAccessDeniedException.class);
        verify(provider, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    private static CurrentActor active(Role role) {
        return new CurrentActor(UUID.randomUUID(), "auth0|" + role.name(), Set.of(role), UserStatus.ACTIVE);
    }
}
