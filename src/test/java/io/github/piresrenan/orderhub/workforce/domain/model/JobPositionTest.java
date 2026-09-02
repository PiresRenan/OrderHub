package io.github.piresrenan.orderhub.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

class JobPositionTest {

    @Test
    void positionCarriesExplicitAuthorityBandAndPermissionCeiling() {

        var envelope =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW));

        var position =
                new JobPosition(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "CATALOG_MANAGER",
                        "Catalog Manager",
                        AuthorityBand.MANAGEMENT,
                        envelope);

        assertThat(position.authorityBand())
                .isEqualTo(
                        AuthorityBand.MANAGEMENT);

        assertThat(
                position.permissionEnvelope()
                        .allows(
                                PermissionCode.CATALOG_VIEW))
                .isTrue();
    }

    @Test
    void higherAuthorityBandDoesNotInventPermissionsOutsideEnvelope() {

        var position =
                new JobPosition(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "LIMITED_MANAGER",
                        "Limited Manager",
                        AuthorityBand.MANAGEMENT,
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW)));

        assertThat(
                position.permissionEnvelope()
                        .allows(
                                PermissionCode.CATALOG_VIEW))
                .isTrue();

        assertThat(
                position.permissionEnvelope()
                        .allows(
                                PermissionCode.TENANT_MEMBERS_MANAGE))
                .isFalse();
    }

    @Test
    void rejectsIncompleteJobPosition() {

        var id =
                UUID.randomUUID();

        var envelope =
                PermissionEnvelope.none();

        assertThatThrownBy(() ->
                new JobPosition(
                        null,
                        id,
                        "POSITION",
                        "Position",
                        AuthorityBand.OPERATIONAL,
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new JobPosition(
                        id,
                        null,
                        "POSITION",
                        "Position",
                        AuthorityBand.OPERATIONAL,
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new JobPosition(
                        id,
                        id,
                        " ",
                        "Position",
                        AuthorityBand.OPERATIONAL,
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new JobPosition(
                        id,
                        id,
                        "POSITION",
                        " ",
                        AuthorityBand.OPERATIONAL,
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new JobPosition(
                        id,
                        id,
                        "POSITION",
                        "Position",
                        null,
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new JobPosition(
                        id,
                        id,
                        "POSITION",
                        "Position",
                        AuthorityBand.OPERATIONAL,
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
