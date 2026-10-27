package io.github.chdurai.agenticbanking.approval;

import java.util.Set;

/** Who is deciding and in which roles. Roles are one of {@link Roles}. */
public record ReviewerIdentity(String id, Set<String> roles) {
    public ReviewerIdentity {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean has(String role) {
        return roles.contains(role) || roles.contains(Roles.PLATFORM_ADMIN);
    }
}
