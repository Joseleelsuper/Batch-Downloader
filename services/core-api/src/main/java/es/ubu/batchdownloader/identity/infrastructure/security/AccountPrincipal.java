package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Principal de sesión que conserva el UUID estable de la cuenta. */
public record AccountPrincipal(UUID userId, String displayUsername, UserRole role)
        implements Principal, UserDetails, Serializable {
    @Serial private static final long serialVersionUID = 2L;

    public AccountPrincipal {
        if (userId == null || displayUsername == null || displayUsername.isBlank() || role == null) {
            throw new IllegalArgumentException("invalid_account_principal");
        }
    }

    public static AccountPrincipal from(UserAccount account) {
        return new AccountPrincipal(account.id(), account.username(), account.role());
    }

    @Override public String getName() { return userId.toString(); }
    @Override public String getUsername() { return userId.toString(); }
    @Override public String getPassword() { return ""; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
