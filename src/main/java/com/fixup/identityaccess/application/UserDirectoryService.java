package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserDirectoryService implements UserDirectory {
    private static final String FALLBACK_NAME = "Usuario de Fix&Rent";
    private final UserAccounts accounts;

    UserDirectoryService(UserAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        var ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        var names = new HashMap<UUID, String>();
        accounts.findAllById(ids).forEach(user -> names.put(user.id(), nameOf(user)));
        return names;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRole(UUID userId, Role role) {
        if (userId == null || role == null) {
            return false;
        }
        return accounts.findById(userId)
                .filter(user -> user.status() == UserStatus.ACTIVE && user.roles().contains(role))
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> activeWithRole(Collection<UUID> userIds, Role role) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return accounts.findAllById(userIds.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .filter(user -> user.status() == UserStatus.ACTIVE && user.roles().contains(role))
                .map(UserAccount::id)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserCard> activeUsersWithRole(Role role) {
        return accounts.findActiveWithRole(role).stream()
                .map(user -> new UserCard(user.id(), nameOf(user)))
                .sorted(Comparator.comparing(UserCard::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String nameOf(UserAccount user) {
        return user.displayName() == null || user.displayName().isBlank() ? FALLBACK_NAME : user.displayName();
    }
}
