package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.domain.CatalogQuery;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.SealSummary;
import com.fixup.properties.domain.WorkRecords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-06 y FR-UC-07: catálogo paginado de inmuebles publicados con filtros y sello. */
@Service
public class BrowseCatalog {
    private final Properties properties;
    private final WorkRecords workRecords;
    private final PropertyCovers covers;
    private final CurrentActorProvider actors;

    BrowseCatalog(Properties properties, WorkRecords workRecords, PropertyCovers covers,
            CurrentActorProvider actors) {
        this.properties = properties;
        this.workRecords = workRecords;
        this.covers = covers;
        this.actors = actors;
    }

    @Transactional(readOnly = true)
    public CatalogPage execute(CatalogQuery query) {
        var actor = actors.currentActor();
        PropertyAccess.requireCanBrowseCatalog(actor);
        var page = properties.searchCatalog(query);
        var ids = page.getContent().stream().map(Property::id).toList();
        var seals = workRecords.sealSummaries(ids);
        var coverUrls = covers.of(page.getContent());
        var items = page.getContent().stream()
                .map(property -> item(property, seals.getOrDefault(property.id(), SealSummary.NONE),
                        coverUrls.get(property.id()), property.isControlledBy(actor.internalUserId())))
                .toList();
        return new CatalogPage(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    static CatalogItem item(Property property, SealSummary seal, String coverUrl, boolean ownedByCurrentUser) {
        var listing = property.listing();
        return new CatalogItem(property.id(), property.name(), property.city(), listing.neighborhood(),
                listing.listingType(), listing.propertyType(), listing.price(), listing.adminFee(),
                listing.bedrooms(), listing.bathrooms(), property.areaM2(), listing.stratum(),
                property.latitude(), property.longitude(), coverUrl, property.photoMediaIds().size(),
                seal.granted(), seal.recentCompletedWorks(), seal.averageRating(), ownedByCurrentUser,
                property.publishedAt());
    }
}
