package com.fixup.quotations.application;

import com.fixup.fixers.api.FixerDirectory;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestNotFoundException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: para comparar ofertas el propietario necesita saber de quién es cada una (nombre,
 * reputación, ciudad), y el técnico necesita saber a qué solicitud corresponde cada cotización suya.
 */
@Service
public class QuotationContext {
    private final FixerDirectory fixers;
    private final RepairRequestDirectory requests;

    QuotationContext(FixerDirectory fixers, RepairRequestDirectory requests) {
        this.fixers = fixers;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public Map<UUID, FixerFacts> fixers(Collection<UUID> fixerUserIds) {
        var facts = new HashMap<UUID, FixerFacts>();
        for (UUID fixerUserId : fixerUserIds) {
            fixers.find(fixerUserId).ifPresent(listing -> facts.put(fixerUserId, new FixerFacts(listing.displayName(),
                    listing.ratingAverage(), listing.ratingCount(), listing.city())));
        }
        return facts;
    }

    /** Títulos de las solicitudes que el llamador todavía puede leer; las demás no aparecen. */
    @Transactional(readOnly = true)
    public Map<UUID, String> requestTitles(Collection<UUID> requestIds) {
        var titles = new HashMap<UUID, String>();
        for (UUID requestId : requestIds) {
            try {
                var request = requests.require(requestId);
                if (request.title() != null) {
                    titles.put(requestId, request.title());
                }
            } catch (RepairRequestNotFoundException notVisible) {
                // Under RLS a request awarded to another fixer is no longer visible to this one.
            }
        }
        return titles;
    }

    public record FixerFacts(String displayName, BigDecimal ratingAverage, int ratingCount, String city) {
    }
}
