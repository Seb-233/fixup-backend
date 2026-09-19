package com.fixup.media;

import com.fixup.media.api.MediaNotFoundException;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaContentTypeValidator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaLifecycleTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MEDIA_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void mediaAssetLifecycleTransitions() {
        var asset = MediaAsset.createPending(MEDIA_ID, OWNER, "FIXER_PORTFOLIO",
                "portfolio/" + OWNER + "/" + MEDIA_ID + ".jpg", "image/jpeg", 1024,
                NOW.plusSeconds(900), NOW);

        assertThat(asset.status()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(asset.isExpired(NOW.plusSeconds(300))).isFalse();
        assertThat(asset.isExpired(NOW.plusSeconds(1000))).isTrue();

        var ready = asset.markReady(NOW.plusSeconds(30));
        assertThat(ready.status()).isEqualTo(MediaAssetStatus.READY);
        assertThat(ready.confirmedAt()).isEqualTo(NOW.plusSeconds(30));

        var attached = ready.markAttached();
        assertThat(attached.status()).isEqualTo(MediaAssetStatus.ATTACHED);

        var deletionPending = attached.markDeletionPending();
        assertThat(deletionPending.status()).isEqualTo(MediaAssetStatus.DELETION_PENDING);

        var deleted = deletionPending.markDeleted();
        assertThat(deleted.status()).isEqualTo(MediaAssetStatus.DELETED);
    }

    @Test
    void foreignUserCannotAccessMedia() {
        var asset = MediaAsset.createPending(MEDIA_ID, OWNER, "FIXER_PORTFOLIO", "key", "image/jpeg", 500, NOW.plusSeconds(900), NOW);
        assertThatThrownBy(() -> asset.requireBelongsTo(UUID.randomUUID()))
                .isInstanceOf(MediaNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
    void allowedContentTypesAreRecognized(String type) {
        assertThat(MediaContentTypeValidator.isAllowed(type)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "application/pdf", "video/mp4", "text/plain", ""})
    void disallowedContentTypesAreRejected(String type) {
        assertThat(MediaContentTypeValidator.isAllowed(type)).isFalse();
    }

    @Test
    void magicBytesValidationAccuratelyDetectsFormats() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] webp = new byte[] {0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
        byte[] exe = new byte[] {0x7F, 0x45, 0x4C, 0x46};

        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/jpeg", jpeg)).isTrue();
        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/jpeg", png)).isFalse();
        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/jpeg", exe)).isFalse();

        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/png", png)).isTrue();
        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/png", jpeg)).isFalse();

        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/webp", webp)).isTrue();
        assertThat(MediaContentTypeValidator.verifyMagicBytes("image/webp", jpeg)).isFalse();
    }
}
