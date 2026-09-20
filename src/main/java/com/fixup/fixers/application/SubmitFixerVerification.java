package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.api.FixerVerificationMedia;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.fixers.domain.VerificationDocument;
import com.fixup.fixers.domain.VerificationDocuments;
import com.fixup.fixers.domain.VerificationPolicy;
import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-23: el técnico archiva sus documentos. Puede subirlos por partes; la revisión
 * administrativa se abre sola en cuanto el conjunto obligatorio queda completo.
 *
 * <p>Sanity checking: cada mediaId debe ser un media asset propio, con purpose FIXER_VERIFICATION y
 * ya en estado READY (lo que a su vez ya exigió un content-type permitido y una firma de bytes
 * coherente en ConfirmUpload). Un mediaId inventado, ajeno o todavía no confirmado hace fallar todo
 * el envío con un error claro; nunca abre la revisión con un documento que nadie validó.
 */
@Service
public class SubmitFixerVerification {
    private final FixerProfiles profiles;
    private final VerificationDocuments documents;
    private final FixerVerificationMedia media;

    SubmitFixerVerification(FixerProfiles profiles, VerificationDocuments documents, FixerVerificationMedia media) {
        this.profiles = profiles;
        this.documents = documents;
        this.media = media;
    }

    @Transactional
    public void execute(CurrentActor actor, List<DocumentSubmission> submissions) {
        FixerAccess.requireActiveFixer(actor);
        var profile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        if (profile.verificationStatus() == FixerVerificationStatus.VERIFIED) {
            throw new FixerVerificationConflictException("ALREADY_VERIFIED",
                    "The fixer profile is already verified");
        }
        if (profile.verificationStatus() == FixerVerificationStatus.SUSPENDED) {
            throw new FixerVerificationConflictException("PROFILE_SUSPENDED",
                    "A suspended fixer profile cannot be submitted for review");
        }

        // Resubmitting the exact same media for a type it is already filed under must stay a no-op:
        // that media asset is already ATTACHED, so re-validating it would fail with a false conflict.
        Map<FixerVerificationDocumentType, UUID> currentMediaByType = new HashMap<>();
        for (var existing : documents.allOf(actor.internalUserId())) {
            currentMediaByType.put(existing.type(), existing.mediaId());
        }
        var newMediaIds = submissions.stream()
                .filter(submission -> !submission.mediaId().equals(currentMediaByType.get(submission.type())))
                .map(DocumentSubmission::mediaId)
                .toList();
        if (!newMediaIds.isEmpty()) {
            media.attachDocuments(actor.internalUserId(), newMediaIds);
        }

        var now = Instant.now();
        for (var submission : submissions) {
            documents.save(new VerificationDocument(actor.internalUserId(), submission.type(),
                    submission.mediaId(), now));
        }
        if (VerificationPolicy.isComplete(documents.typesOf(actor.internalUserId()))) {
            profiles.update(profile.submitForReview(now));
        }
    }
}
