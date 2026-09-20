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
 * FR-UC-23: el tecnico archiva sus documentos. Puede subirlos por partes; la revision
 * administrativa se abre sola en cuanto el conjunto obligatorio queda completo.
 *
 * <p>Consent gate: the fixer must have given explicit consent to personal-data processing before
 * the review can be opened. If consent is supplied in the same request, it is recorded first
 * (idempotent -- historical records are never overwritten). If consent was already on file from a
 * previous call, it is ignored silently. If consent is absent and has never been recorded, the
 * review gate stays closed and a CONSENT_REQUIRED conflict is returned.
 *
 * <p>Sanity checking: cada mediaId debe ser un media asset propio, con purpose FIXER_VERIFICATION y
 * ya en estado READY (lo que a su vez ya exigio un content-type permitido y una firma de bytes
 * coherente en ConfirmUpload). Un mediaId inventado, ajeno o todavia no confirmado hace fallar todo
 * el envio con un error claro; nunca abre la revision con un documento que nadie valido.
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

    /**
     * @param submissions   the documents to file.
     * @param consentVersion the version string of the data-processing terms the fixer is accepting
     *                       now (e.g. "v1.0"). {@code null} or blank means the fixer is not (re-)
     *                       accepting consent in this call; consent must already be on file or the
     *                       submission will fail if the document set would trigger review opening.
     */
    @Transactional
    public void execute(CurrentActor actor, List<DocumentSubmission> submissions, String consentVersion) {
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

        // Record consent first (idempotent). Only writes when no prior consent exists.
        var now = Instant.now();
        if (consentVersion != null && !consentVersion.isBlank()) {
            profile = profile.recordConsent(consentVersion, now);
            profiles.update(profile);
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

        for (var submission : submissions) {
            documents.save(new VerificationDocument(actor.internalUserId(), submission.type(),
                    submission.mediaId(), now));
        }

        var updatedProfile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        if (VerificationPolicy.isComplete(documents.typesOf(actor.internalUserId()))) {
            if (!updatedProfile.hasConsent()) {
                throw new FixerVerificationConflictException("CONSENT_REQUIRED",
                        "Data-processing consent is required before opening the administrative review. "
                                + "Supply consentVersion in the request body.");
            }
            profiles.update(updatedProfile.submitForReview(now));
        }
    }
}