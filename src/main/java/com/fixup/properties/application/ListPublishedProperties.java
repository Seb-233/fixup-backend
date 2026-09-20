package com.fixup.properties.application;

import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Properties;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListPublishedProperties {
    private final Properties properties;

    public ListPublishedProperties(Properties properties) {
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<PropertySummary> execute(PropertyType type, String city, String zone,
            BigDecimal minRent, BigDecimal maxRent, Integer minBedrooms, Integer minBathrooms,
            Double minSurface, int page, int size) {
        int safeSize = size <= 0 ? 50 : Math.min(size, 200);
        int safePage = Math.max(0, page);
        var pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "publishedAt"));
        return properties.findPublished(type, city, zone, minRent, maxRent, minBedrooms, minBathrooms, minSurface, pageable)
                .stream().map(PropertySummary::of).toList();
    }
}
