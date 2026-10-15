package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.time.Instant;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SelectInitialRoleTest {
    private final RoleAssignments assignments = mock(RoleAssignments.class);
    private final UserAccounts accounts = mock(UserAccounts.class);
    private final UserLookup lookup = new UserLookup(accounts);
    private final UUID userId = UUID.randomUUID();
    private final CurrentActor actor = new CurrentActor(userId, "auth0|roles", Set.of(), UserStatus.ACTIVE);

    private UserAccount account(Set<Role> roles, UserStatus status) {
        return new UserAccount(userId, actor.externalSubject(), null, null, status, roles,
                Instant.EPOCH, Instant.EPOCH);
    }

    private SelectInitialRole selection(UserAccount user) {
        when(accounts.findById(userId)).thenReturn(Optional.of(user));
        return new SelectInitialRole(assignments, lookup);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "TENANT", "FIXER"})
    void grantsOnlySelfAssignableRolesToUsersWithoutRoles(Role role) {
        var selection = selection(account(Set.of(), UserStatus.ACTIVE));
        when(assignments.grant(userId, role)).thenReturn(account(Set.of(role), UserStatus.ACTIVE));

        assertThat(selection.execute(actor, role)).containsExactly(role);
        verify(assignments).grant(userId, role);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"PLATFORM_ADMIN", "REAL_ESTATE_MANAGER"})
    void rejectsMissingPrivilegedRolesEvenWithForgedActorRoles(Role role) {
        var selection = selection(account(Set.of(), UserStatus.ACTIVE));
        var forgedActor = new CurrentActor(userId, actor.externalSubject(), Set.of(role), UserStatus.ACTIVE);

        assertThatThrownBy(() -> selection.execute(forgedActor, role))
                .isInstanceOfSatisfying(IdentityProblem.class,
                        problem -> assertThat(problem.reason()).isEqualTo(IdentityProblem.Reason.ACCESS_DENIED));
        verifyNoInteractions(assignments);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void existingDatabaseRolesAreSelectedWithoutGrantAndPreserveAllRoles(Role role) {
        var roles = Set.of(Role.values());
        var selection = selection(account(roles, UserStatus.ACTIVE));

        assertThat(selection.execute(actor, role)).isEqualTo(roles);
        assertThat(selection.execute(actor, role)).isEqualTo(roles);
        verifyNoInteractions(assignments);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DISABLED"})
    void existingRolesDoNotBypassDatabaseAccountStatus(UserStatus status) {
        var selection = selection(account(Set.of(Role.PLATFORM_ADMIN), status));
        var staleActor = new CurrentActor(userId, actor.externalSubject(), Set.of(Role.PLATFORM_ADMIN), UserStatus.ACTIVE);

        assertThatThrownBy(() -> selection.execute(staleActor, Role.PLATFORM_ADMIN))
                .isInstanceOfSatisfying(IdentityProblem.class,
                        problem -> assertThat(problem.reason()).isEqualTo(IdentityProblem.Reason.ACCESS_DENIED));
        verifyNoInteractions(assignments);
    }
}
