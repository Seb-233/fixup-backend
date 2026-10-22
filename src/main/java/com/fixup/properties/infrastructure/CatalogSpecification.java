package com.fixup.properties.infrastructure;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.CatalogQuery;
import com.fixup.properties.domain.SealSummary;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * FR-UC-06: el catálogo se arma con Criteria y no con un JPQL de parámetros opcionales, porque en
 * PostgreSQL un parámetro nulo sin tipo ("? is null") falla al preparar la sentencia.
 */
final class CatalogSpecification {
    private CatalogSpecification() {
    }

    static Specification<PropertyEntity> of(CatalogQuery query) {
        return (root, criteria, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("status"), PropertyStatus.PUBLISHED));
            if (query.city() != null) {
                predicates.add(cb.equal(cb.lower(root.get("city")), query.city().toLowerCase(Locale.ROOT)));
            }
            if (query.listingType() != null) {
                predicates.add(cb.equal(root.get("listingType"), query.listingType()));
            }
            if (query.propertyType() != null) {
                predicates.add(cb.equal(root.get("propertyType"), query.propertyType()));
            }
            if (query.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), query.minPrice()));
            }
            if (query.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), query.maxPrice()));
            }
            if (query.minBedrooms() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("bedrooms"), query.minBedrooms()));
            }
            if (query.stratum() != null) {
                predicates.add(cb.equal(root.get("stratum"), query.stratum()));
            }
            if (query.text() != null) {
                var pattern = "%" + query.text().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("neighborhood"), "")), pattern),
                        cb.like(cb.lower(root.get("city")), pattern)));
            }
            if (query.sealOnly()) {
                var since = Instant.now().minus(SealSummary.WINDOW);
                var recent = criteria.subquery(Long.class);
                var work = recent.from(PropertyWorkRecordEntity.class);
                recent.select(cb.count(work)).where(
                        cb.equal(work.get("propertyId"), root.get("id")),
                        cb.greaterThanOrEqualTo(work.get("completedAt"), since));
                var average = criteria.subquery(Double.class);
                var rated = average.from(PropertyWorkRecordEntity.class);
                average.select(cb.avg(rated.get("rating"))).where(
                        cb.equal(rated.get("propertyId"), root.get("id")),
                        cb.greaterThanOrEqualTo(rated.get("completedAt"), since));
                predicates.add(cb.greaterThan(recent, 0L));
                predicates.add(cb.greaterThanOrEqualTo(cb.coalesce(average, 5.0), SealSummary.MINIMUM_AVERAGE));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static PageRequest pageRequest(CatalogQuery query) {
        var sort = switch (query.sort()) {
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price").nullsLast(), Sort.Order.desc("publishedAt"));
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price").nullsLast(), Sort.Order.desc("publishedAt"));
            case AREA_DESC -> Sort.by(Sort.Order.desc("areaM2"), Sort.Order.desc("publishedAt"));
            case NEWEST -> Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.asc("id"));
        };
        return PageRequest.of(query.page(), query.size(), sort);
    }
}
