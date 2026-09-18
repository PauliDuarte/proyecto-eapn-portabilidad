package py.edu.ucom.is2.eapn.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PinGenerationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T01:30:00Z"),
            ZoneId.of("America/Asuncion"));
    @Mock
    private PortabilityRequestRepository repository;
    @Mock
    private SecureRandom random;
    private PinGenerationService service;

    @BeforeEach
    void setUp() {
        service = new PinGenerationService(repository, CLOCK, random);
    }

    @ParameterizedTest
    @CsvSource({"0, 000000", "7, 000007", "12345, 012345", "999999, 999999"})
    void formatsSixDigitsIncludingLeadingZeros(int value, String expected) {
        when(random.nextInt(1_000_000)).thenReturn(value);
        allowPinUpdate();
        var generated = service.generate(created());
        assertThat(generated.pin()).isEqualTo(expected).matches("[0-9]{6}");
        verify(random).nextInt(1_000_000);
    }

    @Test
    void productionGeneratorProducesSixAsciiDigits() {
        allowPinUpdate();
        var productionService = new PinGenerationService(repository, CLOCK);
        for (int i = 0; i < 20; i++) {
            assertThat(productionService.generate(created()).pin()).matches("[0-9]{6}");
        }
    }

    @Test
    void persistsPinDatesAndStateTogetherUsingConfiguredClock() {
        when(random.nextInt(1_000_000)).thenReturn(42);
        allowPinUpdate();
        var original = created();
        var generated = service.generate(original);

        assertThat(generated.fechaPinGenerado()).isEqualTo(OffsetDateTime.now(CLOCK));
        assertThat(Duration.between(generated.fechaPinGenerado(), generated.pinExpiracion()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(generated.estado()).isEqualTo(PortabilityStatus.PIN_GENERATED);
        assertThat(generated.id()).isEqualTo(original.id());
        assertThat(generated.msisdn()).isEqualTo(original.msisdn());
        assertThat(generated.documentoTitular()).isEqualTo(original.documentoTitular());
        assertThat(generated.operadorDonante()).isEqualTo(original.operadorDonante());
        assertThat(generated.operadorReceptor()).isEqualTo(original.operadorReceptor());
        assertThat(generated.fechaCreacion()).isEqualTo(original.fechaCreacion());
        assertThat(generated.intentosConfirmacion()).isZero();
        assertThat(generated.fechaPinConfirmado()).isNull();
        assertThat(generated.fechaCompletada()).isNull();
        assertThat(generated.motivoRechazo()).isNull();
        verify(repository).updatePinAndState(original.id(), "000042", generated.pinExpiracion(),
                generated.fechaPinGenerado(), PortabilityStatus.PIN_GENERATED);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void doesNotReturnGeneratedRequestIfPersistenceFails() {
        assertThatThrownBy(() -> service.generate(created())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordsFailureWithoutChangingStateOrCompletion() {
        when(repository.updateFailureReason("req-1", "HTTP 503")).thenReturn(1);
        service.recordNotificationFailure("req-1", "HTTP 503");
        verify(repository).updateFailureReason("req-1", "HTTP 503");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void doesNotSilentlyIgnoreFailureToStoreReason() {
        assertThatThrownBy(() -> service.recordNotificationFailure("req-1", "HTTP 503"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void allowPinUpdate() {
        when(repository.updatePinAndState(anyString(), anyString(), any(), any(),
                eq(PortabilityStatus.PIN_GENERATED))).thenReturn(1);
    }

    private PortabilityRequest created() {
        return new PortabilityRequest("req-1", "+595971234567", "0012345", "Tigo", "Personal",
                PortabilityStatus.CREATED, null, null, 0, OffsetDateTime.now(CLOCK).minusMinutes(1),
                null, null, null, null);
    }
}
