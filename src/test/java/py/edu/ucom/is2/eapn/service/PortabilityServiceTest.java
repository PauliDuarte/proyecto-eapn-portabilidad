package py.edu.ucom.is2.eapn.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.dto.CreatePortabilityRequest;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T01:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Asuncion"));

    @Mock
    private PortabilityRequestRepository repository;
    private PortabilityService service;

    @BeforeEach
    void setUp() {
        service = new PortabilityService(repository, CLOCK);
    }

    @Test
    void createsAndPersistsNormalizedRequestWithInitialValues() {
        when(repository.insert(any())).thenReturn(1);
        var saved = service.create(input("+595971234567", " 0012345 ", " tIgO ", " PERSONAL "));

        verify(repository).insert(saved);
        assertThat(saved.id()).matches("REQ-20260917-[0-9a-f]{32}");
        assertThat(saved.msisdn()).isEqualTo("+595971234567");
        assertThat(saved.documentoTitular()).isEqualTo("0012345");
        assertThat(saved.operadorDonante()).isEqualTo("Tigo");
        assertThat(saved.operadorReceptor()).isEqualTo("Personal");
        assertThat(saved.estado()).isEqualTo(PortabilityStatus.CREATED);
        assertThat(saved.fechaCreacion().toInstant()).isEqualTo(NOW);
        assertThat(saved.fechaCreacion().getOffset()).isEqualTo(CLOCK.getZone().getRules().getOffset(NOW));
        assertThat(saved.intentosConfirmacion()).isZero();
        assertThat(saved.pin()).isNull();
        assertThat(saved.pinExpiracion()).isNull();
        assertThat(saved.fechaPinGenerado()).isNull();
        assertThat(saved.fechaPinConfirmado()).isNull();
        assertThat(saved.fechaCompletada()).isNull();
        assertThat(saved.motivoRechazo()).isNull();
    }

    @Test
    void generatesDifferentIdsEvenAtTheSameInstant() {
        when(repository.insert(any())).thenReturn(1);
        var request = input("+595971234567", "12345", "Tigo", "Personal");
        assertThat(service.create(request).id()).isNotEqualTo(service.create(request).id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tigo", "Personal", "Claro", "Vox"})
    void acceptsEachRegisteredOperator(String operator) {
        when(repository.insert(any())).thenReturn(1);
        String other = operator.equals("Tigo") ? "Vox" : "Tigo";
        assertThat(service.create(input("+595971234567", "123", operator, other)).operadorDonante())
                .isEqualTo(operator);
        assertThat(service.create(input("+595971234567", "123", other, operator)).operadorReceptor())
                .isEqualTo(operator);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestWithoutCallingRepository(CreatePortabilityRequest request, String message) {
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(RequestValidationException.class).hasMessageContaining(message);
        verifyNoInteractions(repository);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(null, "cuerpo"),
                Arguments.of(input(null, "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("595971234567", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+549971234567", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+5950971234567", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+59597123456", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+5959712345678", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+59597123abcd", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+595971 234567", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input(" +595971234567", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+595211234567", "123", "Tigo", "Personal"), "msisdn"),
                Arguments.of(input("+595971234567", null, "Tigo", "Personal"), "documento_titular"),
                Arguments.of(input("+595971234567", "", "Tigo", "Personal"), "documento_titular"),
                Arguments.of(input("+595971234567", " \t\n", "Tigo", "Personal"), "documento_titular"),
                Arguments.of(input("+595971234567", "x".repeat(51), "Tigo", "Personal"), "50 caracteres"),
                Arguments.of(input("+595971234567", "123", "Otro", "Personal"), "operador_donante"),
                Arguments.of(input("+595971234567", "123", null, "Personal"), "operador_donante"),
                Arguments.of(input("+595971234567", "123", "  ", "Personal"), "operador_donante"),
                Arguments.of(input("+595971234567", "123", "Tigo", "Otro"), "operador_receptor"),
                Arguments.of(input("+595971234567", "123", "Tigo", null), "operador_receptor"),
                Arguments.of(input("+595971234567", "123", "Tigo", ""), "operador_receptor"),
                Arguments.of(input("+595971234567", "123", "Tigo", " tIGO "), "diferentes"));
    }

    @Test
    void propagatesPersistenceFailure() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(repository.insert(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.create(input("+595971234567", "123", "Tigo", "Personal")))
                .isSameAs(failure);
    }

    @Test
    void doesNotReportSuccessWhenNoRowWasInserted() {
        when(repository.insert(any())).thenReturn(0);
        assertThatThrownBy(() -> service.create(input("+595971234567", "123", "Tigo", "Personal")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static CreatePortabilityRequest input(String msisdn, String document, String donor, String receiver) {
        return new CreatePortabilityRequest(msisdn, document, donor, receiver);
    }
}
