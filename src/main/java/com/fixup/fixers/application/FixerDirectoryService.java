package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerDirectory;
import com.fixup.fixers.api.FixerReputation;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerCard;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class FixerDirectoryService implements FixerDirectory, FixerReputation {
    private final FixerProfiles profiles;
    private final UserDirectory users;

    FixerDirectoryService(FixerProfiles profiles, UserDirectory users) {
        this.profiles = profiles;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FixerListing> verified(Specialty specialty, String city) {
        var cards = profiles.findVerifiedCards(specialty, city);
        var ids = cards.stream().map(card -> card.profile().userId()).toList();
        var active = users.activeWithRole(ids, Role.FIXER);
        var names = users.displayNames(ids);
        return cards.stream()
                .filter(card -> active.contains(card.profile().userId()))
                .map(card -> listing(card, names))
                .sorted(Comparator.comparing((FixerListing listing) ->
                                listing.ratingAverage() == null ? BigDecimal.ZERO : listing.ratingAverage())
                        .reversed()
                        .thenComparing(FixerListing::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FixerListing> find(UUID fixerUserId) {
        return profiles.findCard(fixerUserId)
                .map(card -> listing(card, users.displayNames(List.of(fixerUserId))));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReputation(UUID fixerUserId, BigDecimal ratingAverage, int ratingCount) {
        profiles.updateReputation(fixerUserId, ratingAverage, ratingCount, Instant.now());
    }

    private static FixerListing listing(FixerCard card, Map<UUID, String> names) {
        var profile = card.profile();
        return new FixerListing(profile.userId(), names.getOrDefault(profile.userId(), "Técnico de Fix&Rent"),
                profile.verificationStatus(), profile.specialties(), profile.city(), card.bio(),
                card.yearsExperience(), card.ratingAverage(), card.ratingCount());
    }
}
