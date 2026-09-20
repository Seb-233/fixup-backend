package com.fixup.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixup.properties.api.PropertyConflictException;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Property;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PropertyTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2025-09-19T12:00:00Z");

    private static Property.Builder minimalApartment() {
        return Property.builder()
                .id(UUID.randomUUID())
                .ownerUserId(OWNER)
                .type(PropertyType.APARTMENT)
                .title("Departamento 2 ambientes")
                .city("Buenos Aires")
                .zone("Palermo")
                .monthlyRentSuggestion(BigDecimal.valueOf(850000));
    }

    @Test
    void createsMinimalApartment() {
        var p = minimalApartment().build();
        assertThat(p.status()).isEqualTo(PropertyStatus.DRAFT);
        assertThat(p.createdAt()).isEqualTo(NOW);
        assertThat(p.ownerUserId()).isEqualTo(OWNER);
        assertThat(p.amenities()).isEmpty();
        assertThat(p.mediaIds()).isEmpty();
    }

    @Test
    void nullManagerIsAllowed() {
        var p = minimalApartment().managerUserId(null).build();
        assertThat(p.managerUserId()).isNull();
    }

    @Test
    void titleIsRequired() {
        assertThatThrownBy(() -> minimalApartment().title(null).build())
                .isInstanceOf(PropertyConflictException.class)
                .hasMessageContaining("required")
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("INVALID_TITLE");
    }

    @Test
    void titleTooLongIsRejected() {
        assertThatThrownBy(() -> minimalApartment().title("a".repeat(151)).build())
                .isInstanceOf(PropertyConflictException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void typeIsRequired() {
        assertThatThrownBy(() -> minimalApartment().type(null).build())
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("INVALID_TYPE");
    }

    @Test
    void cityIsRequired() {
        assertThatThrownBy(() -> minimalApartment().city(null).build())
                .isInstanceOf(PropertyConflictException.class)
                .hasMessageContaining("city");
    }

    @Test
    void zoneIsRequired() {
        assertThatThrownBy(() -> minimalApartment().zone("").build())
                .isInstanceOf(PropertyConflictException.class)
                .hasMessageContaining("zone");
    }

    @Test
    void negativeRentIsRejected() {
        assertThatThrownBy(() -> minimalApartment().monthlyRentSuggestion(BigDecimal.valueOf(-1)).build())
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("INVALID_RENT");
    }

    @Test
    void negativeSurfaceIsRejected() {
        assertThatThrownBy(() -> minimalApartment().surfaceM2(-2.0).build())
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("INVALID_SURFACE");
    }

    @Test
    void negativeBedroomsIsRejected() {
        assertThatThrownBy(() -> minimalApartment().bedrooms(-1).build())
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("INVALID_BEDROOMS");
    }

    @Test
    void mediaIdsAreCappedAt30() {
        var tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(31).toList();
        assertThatThrownBy(() -> minimalApartment().mediaIds(tooMany).build())
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("TOO_MANY_MEDIA");
    }

    @Test
    void amenitiesSanitized() {
        var raw = new java.util.ArrayList<String>();
        raw.add("Balcon ");
        raw.add("");
        raw.add(null);
        raw.add("a".repeat(100));
        raw.add("   PISCINA   ");
        var p = minimalApartment().amenities(raw).build();
        assertThat(p.amenities()).containsExactly("Balcon", "PISCINA");
    }

    @Test
    void publishSetsPublishedStatus() {
        var p = minimalApartment().build();
        var published = p.publish(NOW);
        assertThat(published.status()).isEqualTo(PropertyStatus.PUBLISHED);
        assertThat(published.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void publishRequiresRent() {
        var p = minimalApartment().monthlyRentSuggestion(null).build();
        assertThatThrownBy(() -> p.publish(NOW))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("CANNOT_PUBLISH");
    }

    @Test
    void publishRequiresPositiveRent() {
        var p = minimalApartment().monthlyRentSuggestion(BigDecimal.ZERO).build();
        assertThatThrownBy(() -> p.publish(NOW))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("CANNOT_PUBLISH");
    }

    @Test
    void publishDeletedIsRejected() {
        var p = minimalApartment().build().delete(NOW);
        assertThatThrownBy(() -> p.publish(NOW.plusSeconds(1)))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("DELETED_PROPERTY");
    }

    @Test
    void unlistRequiresPublished() {
        var draft = minimalApartment().build();
        assertThatThrownBy(() -> draft.unlist(NOW))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("NOT_PUBLISHED");
    }

    @Test
    void unlistSetsUnlistedStatus() {
        var p = minimalApartment().build().publish(NOW).unlist(NOW.plusSeconds(60));
        assertThat(p.status()).isEqualTo(PropertyStatus.UNLISTED);
        assertThat(p.unlistedAt()).isNotNull();
    }

    @Test
    void relistRequiresUnlisted() {
        var published = minimalApartment().build().publish(NOW);
        assertThatThrownBy(() -> published.relist(NOW))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("NOT_UNLISTED");
    }

    @Test
    void relistSetsPublishedAgain() {
        var relisted = minimalApartment().build()
                .publish(NOW)
                .unlist(NOW.plusSeconds(60))
                .relist(NOW.plusSeconds(3600));
        assertThat(relisted.status()).isEqualTo(PropertyStatus.PUBLISHED);
        assertThat(relisted.unlistedAt()).isNull();
    }

    @Test
    void deleteIsIdempotentOnlyForDeletedState() {
        var p = minimalApartment().build();
        var deleted = p.delete(NOW);
        assertThat(deleted.status()).isEqualTo(PropertyStatus.DELETED);
        assertThat(deleted.deletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> deleted.delete(NOW.plusSeconds(1)))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("ALREADY_DELETED");
    }

    @Test
    void updateCannotBeAppliedToDeleted() {
        var deleted = minimalApartment().build().delete(NOW);
        var patch = new Property.UpdatePatch(
                null, "Updated", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> deleted.update(patch, NOW))
                .isInstanceOf(PropertyConflictException.class)
                .extracting(e -> ((PropertyConflictException) e).getCode())
                .isEqualTo("DELETED_PROPERTY");
    }

    @Test
    void updateAppliesNonNullFields() {
        var p = minimalApartment().build();
        var patch = new Property.UpdatePatch(
                PropertyType.STUDIO, "New Title", null, null, null, null, null,
                "CABA", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                BigDecimal.valueOf(200000), null, null);
        var updated = p.update(patch, NOW.plusSeconds(1));
        assertThat(updated.type()).isEqualTo(PropertyType.STUDIO);
        assertThat(updated.title()).isEqualTo("New Title");
        assertThat(updated.city()).isEqualTo("CABA");
        assertThat(updated.zone()).isEqualTo("Palermo");
        assertThat(updated.monthlyRentSuggestion()).isEqualByComparingTo("200000");
        assertThat(updated.updatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void isOwnedOrManagedByHandlesOwnerAndManager() {
        var p = minimalApartment().managerUserId(MANAGER).build();
        assertThat(p.isOwnedOrManagedBy(OWNER)).isTrue();
        assertThat(p.isOwnedOrManagedBy(MANAGER)).isTrue();
        assertThat(p.isOwnedOrManagedBy(UUID.randomUUID())).isFalse();
        assertThat(p.isOwnedOrManagedBy(null)).isFalse();
    }
}
