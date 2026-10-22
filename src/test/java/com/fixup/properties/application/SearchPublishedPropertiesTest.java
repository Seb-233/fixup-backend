package com.fixup.properties.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.domain.GeographicBoundingBox;
import com.fixup.properties.domain.OwnershipScope;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.PropertySearchMatch;
import com.fixup.properties.domain.WorkRecords;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SearchPublishedPropertiesTest {
    private final Properties properties = mock(Properties.class);
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final WorkRecords workRecords = mock(WorkRecords.class);
    private final PropertyCovers covers = mock(PropertyCovers.class);
    private final SearchPublishedProperties search =
            new SearchPublishedProperties(properties, actors, workRecords, covers);

    @ParameterizedTest
    @ValueSource(strings = {"-90.000001", "90.000001"})
    void rejectsLatitudeOutsideEarth(String value) {
        assertThatThrownBy(() -> search.execute(
                        new BigDecimal(value), BigDecimal.ZERO, BigDecimal.ONE, OwnershipScope.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
        verify(actors, never()).currentActor();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-180.000001", "180.000001"})
    void rejectsLongitudeOutsideEarth(String value) {
        assertThatThrownBy(() -> search.execute(
                        BigDecimal.ZERO, new BigDecimal(value), BigDecimal.ONE, OwnershipScope.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
        verify(actors, never()).currentActor();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.001"})
    void rejectsNonPositiveRadius(String value) {
        assertThatThrownBy(() -> search.execute(
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(value), OwnershipScope.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radiusKm");
        verify(actors, never()).currentActor();
    }

    @Test
    void calculatesBoundingBoxAndDelegatesForAnActiveActor() {
        var actor = new CurrentActor(UUID.randomUUID(), "auth0|search", Set.of(Role.OWNER), UserStatus.ACTIVE);
        when(actors.currentActor()).thenReturn(actor);
        when(properties.searchPublished(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        search.execute(
                new BigDecimal("4.7110"),
                new BigDecimal("-74.0721"),
                new BigDecimal("10"),
                OwnershipScope.OTHERS);

        var boxCaptor = ArgumentCaptor.forClass(GeographicBoundingBox.class);
        verify(properties).searchPublished(
                org.mockito.ArgumentMatchers.eq(new BigDecimal("4.7110")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("-74.0721")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("10")),
                org.mockito.ArgumentMatchers.eq(actor.internalUserId()),
                org.mockito.ArgumentMatchers.eq(OwnershipScope.OTHERS),
                boxCaptor.capture());
        var box = boxCaptor.getValue();
        double latitudeDelta = Math.toDegrees(10.0 / SearchPublishedProperties.EARTH_RADIUS_KM);
        assertThat(box.minLatitude().doubleValue())
                .isCloseTo(4.7110 - latitudeDelta, within(0.0000001));
        assertThat(box.maxLatitude().doubleValue())
                .isCloseTo(4.7110 + latitudeDelta, within(0.0000001));
        assertThat(box.minLongitude().doubleValue()).isLessThan(-74.0721);
        assertThat(box.maxLongitude().doubleValue()).isGreaterThan(-74.0721);
        assertThat(box.allLongitudes()).isFalse();
        assertThat(box.crossesAntimeridian()).isFalse();
    }

    @Test
    void defaultsNullScopeToAllAndMarksOnlyOwnerResultsAsOwned() {
        UUID ownerId = UUID.randomUUID();
        var actor = new CurrentActor(ownerId, "auth0|owner", Set.of(Role.OWNER), UserStatus.ACTIVE);
        var own = Property.create(ownerId, "Mine", "Address", "Bogota", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO);
        var other = Property.create(UUID.randomUUID(), "Other", "Address", "Bogota", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO);
        when(actors.currentActor()).thenReturn(actor);
        when(properties.searchPublished(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new PropertySearchMatch(own, 0.0), new PropertySearchMatch(other, 0.5)));

        var results = search.execute(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, null);

        assertThat(results).extracting(PropertySearchResult::ownedByCurrentUser)
                .containsExactly(true, false);
        verify(properties).searchPublished(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                ownerId,
                OwnershipScope.ALL,
                SearchPublishedProperties.boundingBox(0, 0, 1));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = OwnershipScope.class, names = {"MINE", "OTHERS"})
    void tenantCannotUseOwnerSpecificScopes(OwnershipScope scope) {
        when(actors.currentActor()).thenReturn(activeActor(Role.TENANT));

        assertThatThrownBy(() -> search.execute(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, scope))
                .isInstanceOf(PropertyAccessDeniedException.class);
        verify(properties, never()).searchPublished(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = Role.class, names = {"FIXER"})
    void deniesRolesWithoutPropertySearchAccess(Role role) {
        when(actors.currentActor()).thenReturn(activeActor(role));

        assertThatThrownBy(() -> search.execute(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, OwnershipScope.ALL))
                .isInstanceOf(PropertyAccessDeniedException.class);
        verify(properties, never()).searchPublished(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Role.class, names = {"TENANT", "PLATFORM_ADMIN"})
    void tenantAndAdministratorCanSearchAll(Role role) {
        var actor = activeActor(role);
        when(actors.currentActor()).thenReturn(actor);
        when(properties.searchPublished(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        search.execute(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, OwnershipScope.ALL);

        verify(properties).searchPublished(
                org.mockito.ArgumentMatchers.eq(BigDecimal.ZERO),
                org.mockito.ArgumentMatchers.eq(BigDecimal.ZERO),
                org.mockito.ArgumentMatchers.eq(BigDecimal.ONE),
                org.mockito.ArgumentMatchers.eq(actor.internalUserId()),
                org.mockito.ArgumentMatchers.eq(OwnershipScope.ALL),
                any());
    }

    @Test
    void usesAllLongitudesAtThePoleWithoutInvalidDivision() {
        var box = SearchPublishedProperties.boundingBox(90.0, 25.0, 5.0);

        assertThat(box.maxLatitude()).isEqualByComparingTo("90.0");
        assertThat(box.minLongitude()).isEqualByComparingTo("-180");
        assertThat(box.maxLongitude()).isEqualByComparingTo("180");
        assertThat(box.allLongitudes()).isTrue();
        assertThat(box.crossesAntimeridian()).isFalse();
    }

    @Test
    void representsAnAntimeridianCrossingWithoutExpandingTheBox() {
        var box = SearchPublishedProperties.boundingBox(0.0, 179.9, 30.0);

        assertThat(box.allLongitudes()).isFalse();
        assertThat(box.crossesAntimeridian()).isTrue();
        assertThat(box.minLongitude()).isPositive();
        assertThat(box.maxLongitude()).isNegative();
    }

    @Test
    void usesTheWholeGlobeWhenRadiusReachesHalfTheEarthCircumference() {
        var box = SearchPublishedProperties.boundingBox(
                0.0,
                25.0,
                Math.PI * SearchPublishedProperties.EARTH_RADIUS_KM);

        assertThat(box.minLatitude()).isEqualByComparingTo("-90.0");
        assertThat(box.maxLatitude()).isEqualByComparingTo("90.0");
        assertThat(box.allLongitudes()).isTrue();
        assertThat(box.crossesAntimeridian()).isFalse();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private static CurrentActor activeActor(Role role) {
        return new CurrentActor(UUID.randomUUID(), "auth0|" + role.name(), Set.of(role), UserStatus.ACTIVE);
    }
}
