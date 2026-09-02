package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

/**
 * Organizational authority ceiling used by authorization policy.
 *
 * <p>
 * A higher band may enlarge delegation/permission ceilings, but never
 * automatically inherits the operational permissions of a lower band.
 * </p>
 */
@NamedInterface("policy-model")
public enum AuthorityBand {

    OPERATIONAL(10),
    SUPERVISORY(20),
    COORDINATION(30),
    MANAGEMENT(40),
    TENANT_GOVERNANCE(50);

    private final int rank;

    AuthorityBand(
            int rank) {

        this.rank = rank;
    }

    /**
     * Determines whether this authority band is at least as high as another
     * authority band.
     *
     * <p>
     * This comparison expresses organizational authority only. It does not
     * grant permissions.
     * </p>
     *
     * @param other authority band being compared
     * @return true when this band is at least the requested organizational rank
     */
    public boolean isAtLeast(
            AuthorityBand other) {

        if (other == null) {
            throw new IllegalArgumentException(
                    "Authority band is required");
        }

        return rank >= other.rank;
    }
}
