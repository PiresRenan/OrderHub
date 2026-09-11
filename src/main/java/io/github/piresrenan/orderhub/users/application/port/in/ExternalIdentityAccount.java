package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/** Private account-settings projection; subject and credentials are never exposed. */
public record ExternalIdentityAccount(UUID bindingId, String issuer) {
    /** Redacts credential or provider identity data from incidental textual logging. */
    @Override public String toString() { return "ExternalIdentityAccount[issuer=REDACTED]"; }
}
