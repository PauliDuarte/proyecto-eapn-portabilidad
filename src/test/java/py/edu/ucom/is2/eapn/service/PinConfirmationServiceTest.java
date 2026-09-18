package py.edu.ucom.is2.eapn.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinRequest;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static py.edu.ucom.is2.eapn.service.PinConfirmationException.Reason.*;

@ExtendWith(MockitoExtension.class)
class PinConfirmationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T01:30:00Z"),
            ZoneId.of("America/Asuncion"));
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    private static final String ID = "REQ-test";

    @Mock
    private PortabilityRequestRepository repository;
    private PinConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new PinConfirmationService(repository, CLOCK);
    }

    @Test
    void correctPinConfirmsWithConfiguredClockAndOneAtomicAttemptUpdate() {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, "000042", NOW.plusMinutes(1), NOW.minusMinutes(14)));
        when(repository.confirmPin(ID, NOW)).thenReturn(1);

        var result = service.confirm(ID, new ConfirmPinRequest("000042"));

        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.estado()).isEqualTo(PortabilityStatus.CONFIRMED);
        assertThat(result.mensaje()).isEqualTo("PIN confirmado correctamente");
        var order = inOrder(repository);
        order.verify(repository).findById(ID);
        order.verify(repository).confirmPin(ID, NOW);
        order.verifyNoMoreInteractions();
    }

    @Test
    void incorrectPinRejectsWithReasonAndOneAtomicAttemptUpdate() {
        givenValidRequest();
        when(repository.rejectPinConfirmation(ID, "El PIN es incorrecto.")).thenReturn(1);

        assertFailure("999999", INCORRECT_PIN, "El PIN es incorrecto.");

        verify(repository).findById(ID);
        verify(repository).rejectPinConfirmation(ID, "El PIN es incorrecto.");
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void rejectsExpirationIncludingExactBoundary(long secondsUntilExpiry) {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, "000042", NOW.plusSeconds(secondsUntilExpiry),
                NOW.minusMinutes(15)));
        when(repository.rejectPinConfirmation(ID, "El PIN ha expirado.")).thenReturn(1);

        assertFailure("000042", EXPIRED_PIN, "El PIN ha expirado.");

        verify(repository).findById(ID);
        verify(repository).rejectPinConfirmation(ID, "El PIN ha expirado.");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void checksExpirationByInstantAcrossOffsets() {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, "000042",
                NOW.withOffsetSameInstant(ZoneOffset.UTC), NOW.minusMinutes(15)));
        when(repository.rejectPinConfirmation(ID, "El PIN ha expirado.")).thenReturn(1);
        assertFailure("000042", EXPIRED_PIN, "El PIN ha expirado.");
    }

    @Test
    void acceptsPinJustBeforeExpiration() {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, "000042", NOW.plusNanos(1), NOW.minusMinutes(14)));
        when(repository.confirmPin(ID, NOW)).thenReturn(1);
        assertThat(service.confirm(ID, new ConfirmPinRequest("000042")).estado())
                .isEqualTo(PortabilityStatus.CONFIRMED);
    }

    @Test
    void expirationTakesPrecedenceOverIncorrectPin() {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, "000042", NOW, NOW.minusMinutes(15)));
        when(repository.rejectPinConfirmation(ID, "El PIN ha expirado.")).thenReturn(1);
        assertFailure("999999", EXPIRED_PIN, "El PIN ha expirado.");
    }

    @Test
    void missingRequestDoesNotWrite() {
        when(repository.findById(ID)).thenReturn(Optional.empty());
        assertFailure("000042", NOT_FOUND, "La solicitud de portabilidad no existe.");
        verifyReadOnly();
    }

    @ParameterizedTest
    @EnumSource(value = PortabilityStatus.class, names = "PIN_GENERATED", mode = EnumSource.Mode.EXCLUDE)
    void otherStatesDoNotWriteOrCountAttempt(PortabilityStatus status) {
        givenRequest(request(status, "000042", NOW.plusMinutes(1), NOW.minusMinutes(14)));
        assertFailure("000042", INVALID_STATE, "Solo se puede confirmar una solicitud en estado PIN_GENERATED.");
        verifyReadOnly();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "12a456", " 123456", "123456 ", "１２３４５６"})
    void malformedPinsDoNotWriteOrCountAttempt(String pin) {
        givenValidRequest();
        assertFailure(pin, MALFORMED_PIN, "pin debe contener exactamente 6 dígitos.");
        verifyReadOnly();
    }

    @Test
    void nullBodyDoesNotCountAttempt() {
        givenValidRequest();
        assertThatThrownBy(() -> service.confirm(ID, null)).isInstanceOfSatisfying(PinConfirmationException.class,
                error -> assertThat(error.reason()).isEqualTo(MALFORMED_PIN));
        verifyReadOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pin", "expiry", "generated"})
    void missingGeneratedPinDataDoesNotWrite(String missingField) {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, missingField.equals("pin") ? null : "000042",
                missingField.equals("expiry") ? null : NOW.plusMinutes(1),
                missingField.equals("generated") ? null : NOW.minusMinutes(14)));
        assertFailure("000042", MISSING_PIN, "La solicitud no tiene un PIN generado con sus fechas de vigencia.");
        verifyReadOnly();
    }

    @Test
    void concurrentStateChangeDoesNotReportSuccess() {
        givenValidRequest();
        // El UPDATE condicional no encontró PIN_GENERATED y no incrementó intentos.
        when(repository.confirmPin(ID, NOW)).thenReturn(0);
        assertFailure("000042", STATE_CHANGED, "La solicitud cambió de estado y ya no se puede confirmar su PIN.");
        verify(repository).findById(ID);
        verify(repository).confirmPin(ID, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void concurrentStateChangeDoesNotReportRejectionAsPersisted() {
        givenValidRequest();
        when(repository.rejectPinConfirmation(ID, "El PIN es incorrecto.")).thenReturn(0);
        assertFailure("999999", STATE_CHANGED, "La solicitud cambió de estado y ya no se puede confirmar su PIN.");
    }

    @Test
    void persistenceFailureIsNotReportedAsPinRejection() {
        givenValidRequest();
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(repository.confirmPin(ID, NOW)).thenThrow(failure);
        assertThatThrownBy(() -> service.confirm(ID, new ConfirmPinRequest("000042"))).isSameAs(failure);
    }

    private void assertFailure(String pin, PinConfirmationException.Reason reason, String message) {
        assertThatThrownBy(() -> service.confirm(ID, new ConfirmPinRequest(pin)))
                .isInstanceOfSatisfying(PinConfirmationException.class, error -> {
                    assertThat(error.reason()).isEqualTo(reason);
                    assertThat(error.getMessage()).isEqualTo(message);
                });
    }

    private void givenValidRequest() {
        givenRequest(request(PortabilityStatus.PIN_GENERATED, "000042", NOW.plusMinutes(1), NOW.minusMinutes(14)));
    }

    private void givenRequest(PortabilityRequest request) {
        when(repository.findById(ID)).thenReturn(Optional.of(request));
    }

    private void verifyReadOnly() {
        verify(repository).findById(ID);
        verifyNoMoreInteractions(repository);
    }

    private PortabilityRequest request(PortabilityStatus status, String pin, OffsetDateTime expiry,
            OffsetDateTime generated) {
        return new PortabilityRequest(ID, "+595971234567", "12345", "Tigo", "Personal", status,
                pin, expiry, 2, NOW.minusMinutes(20), generated, null, null, null);
    }
}
