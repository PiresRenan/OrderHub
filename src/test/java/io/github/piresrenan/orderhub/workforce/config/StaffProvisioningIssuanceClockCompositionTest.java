package io.github.piresrenan.orderhub.workforce.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

/**
 * Why: Application and database clocks can differ in either direction.
 * Covers: Production issuance composition calculates persisted and returned expiry from database time.
 * Prevents: Host clock skew from invalidating issuance or changing the configured proof lifetime.
 */
class StaffProvisioningIssuanceClockCompositionTest {
    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "2099-01-01T00:00:00Z"})
    void expiryUsesDatabaseTimeEvenWhenHostTimeDiffers(String databaseTimestamp) {
        var databaseNow = OffsetDateTime.parse(databaseTimestamp);
        var ttl = Duration.ofMinutes(30);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)).thenReturn(databaseNow);
        var repository = mock(StaffProvisioningIntentRepository.class);
        when(repository.create(any())).thenAnswer(invocation -> {
            NewStaffProvisioningIntent intent = invocation.getArgument(0);
            assertThat(intent.expiresAt()).isEqualTo(databaseNow.plus(ttl));
            return new StaffProvisioningIntentCreation.Created(intent.intentId());
        });
        var issuance = new WorkforceConfiguration().issueStaffProvisioningIntentUseCase(
                repository, new StaffProvisioningProperties(ttl), jdbc);

        var result = issuance.issue(new IssueStaffProvisioningIntentCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(result).isInstanceOfSatisfying(StaffProvisioningIssuance.Issued.class,
                issued -> assertThat(issued.expiresAt()).isEqualTo(databaseNow.plus(ttl)));
    }
}
