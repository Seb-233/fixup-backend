package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationMedia;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.fixers.domain.VerificationDocument;
import com.fixup.fixers.domain.VerificationDocuments;
import com.fixup.identityaccess.api.CurrentActor;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-23: lets a PLATFORM_ADMIN see the identity and specialties a fixer registered -- including
 * the submitted documents themselves -- before deciding. Admin-only for the same reason as
 * ReviewFixerVerification: RLS on fixer_verification_documents already blocks anyone else from
 * reading the documents at the database level, and this method-security check backs that up before
 * the query is even issued.
 *
 * <p>Each document's readUrl is a short-lived presigned GET, not a permanent link: it is produced by
 * the same media-module code path (DefaultMediaAttachmentService.resolveReadUrls, behind {@link
 * FixerVerificationMedia#resolveReadUrls}) used for repair-request photos, whose signature expires 15
 * minutes after it is issued (READ_EXPIRATION there). An admin who leaves a review screen open, or a
 * leaked link, stops having access within minutes rather than indefinitely. See
 * FixerVerificationHttpContract#anAdministratorCanViewTheFixersRegisteredDocumentsBeforeDeciding for
 * the test asserting this bound.
 */
@Service
public class GetFixerVerificationForReview {
    private final FixerProfiles profiles;
    private final VerificationDocuments documents;
    private final FixerVerificationMedia media;

    GetFixerVerificationForReview(FixerProfiles profiles, VerificationDocuments documents,
            FixerVerificationMedia media) {
        this.profiles = profiles;
        this.documents = documents;
        this.media = media;
    }

    @PreAuthorize("@internalAuthorization.isCurrentAdmin(#actor)")
    @Transactional(readOnly = true)
    public FixerVerificationReviewView execute(CurrentActor actor, UUID fixerUserId) {
        var profile = profiles.findByUserId(fixerUserId)
                .orElseThrow(() -> new FixerVerificationConflictException("PROFILE_NOT_FOUND",
                        "There is no fixer profile with that identifier"));
        var submitted = documents.allOf(fixerUserId);
        var readUrls = media.resolveReadUrls(submitted.stream().map(VerificationDocument::mediaId).toList());
        var byMediaId = readUrls.stream()
                .collect(Collectors.toMap(FixerVerificationMedia.SignedDocument::mediaId, view -> view));

        var views = submitted.stream()
                .map(document -> {
                    var signed = byMediaId.get(document.mediaId());
                    return new FixerVerificationReviewView.SubmittedDocumentView(document.type(), document.mediaId(),
                            signed == null ? null : signed.readUrl(),
                            signed == null ? null : signed.readUrlExpiresAt());
                })
                .toList();

        return new FixerVerificationReviewView(fixerUserId, profile.verificationStatus(), profile.isUnderReview(),
                profile.submittedAt(), profile.decidedAt(), profile.decidedBy(), profile.rejectionReason(),
                profile.specialties(), views);
    }
}
