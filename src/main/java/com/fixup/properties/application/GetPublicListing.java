package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.SealSummary;
import com.fixup.properties.domain.WorkRecord;
import com.fixup.properties.domain.WorkRecords;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetPublicListing {
    private final Properties properties;
    private final WorkRecords workRecords;
    private final MediaAttachmentService media;
    private final UserDirectory users;
    private final CurrentActorProvider actors;

    GetPublicListing(Properties properties, WorkRecords workRecords, MediaAttachmentService media,
            UserDirectory users, CurrentActorProvider actors) {
        this.properties = properties;
        this.workRecords = workRecords;
        this.media = media;
        this.users = users;
        this.actors = actors;
    }

    @Transactional(readOnly = true)
    public PublicListingView execute(UUID propertyId) {
        var actor = actors.currentActor();
        var property = properties.findById(propertyId).orElseThrow(() -> new PropertyNotFoundException(propertyId));
        PropertyAccess.requireCanReadListing(actor, property);

        var photos = media.resolveReadUrls(property.photoMediaIds()).stream()
                .map(view -> new PublicListingView.Photo(view.mediaId(), view.readUrl()))
                .toList();
        var history = workRecords.findByProperty(propertyId);
        var seal = seal(history);
        var people = new ArrayList<UUID>();
        people.add(property.ownerUserId());
        history.forEach(work -> people.add(work.fixerUserId()));
        var names = users.displayNames(people);
        var works = history.stream()
                .map(work -> new PublicListingView.Work(work.jobId(), work.specialty(), work.title(),
                        work.fixerUserId(), names.get(work.fixerUserId()), work.completedAt(), work.rating()))
                .toList();
        var listing = property.listing();
        return new PublicListingView(property.id(), property.name(), property.address(), property.city(),
                listing.neighborhood(), property.areaM2(), property.status(), listing.listingType(),
                listing.propertyType(), listing.price(), listing.adminFee(), listing.bedrooms(),
                listing.bathrooms(), listing.parkingSpots(), listing.stratum(), listing.description(),
                property.latitude(), property.longitude(), property.publishedAt(),
                names.get(property.ownerUserId()), photos,
                new PublicListingView.Seal(seal.granted(), seal.recentCompletedWorks(), seal.averageRating(),
                        seal.lastWorkAt()),
                works, property.isControlledBy(actor.internalUserId()));
    }

    static SealSummary seal(List<WorkRecord> history) {
        var since = Instant.now().minus(SealSummary.WINDOW);
        var recent = history.stream().filter(work -> !work.completedAt().isBefore(since)).toList();
        if (recent.isEmpty()) {
            return SealSummary.NONE;
        }
        var average = recent.stream().filter(work -> work.rating() != null)
                .mapToInt(WorkRecord::rating).average();
        return new SealSummary(recent.size(), average.isPresent() ? average.getAsDouble() : null,
                recent.get(0).completedAt());
    }
}
