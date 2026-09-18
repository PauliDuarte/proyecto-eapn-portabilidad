package py.edu.ucom.is2.eapn.route;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import javax.sql.DataSource;
import jakarta.jms.ConnectionFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "camel.springboot.main-run-controller=false")
class PinConfirmationRouteTest {

    private static final String ID = "REQ-confirm-test";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-18T01:30:00Z"),
            ZoneId.of("America/Asuncion"));
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED_CLOCK);

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private PortabilityRequestRepository repository;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private DataSource dataSource;
    @MockitoBean
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void setClock() {
        when(clock.instant()).thenReturn(FIXED_CLOCK.instant());
        when(clock.getZone()).thenReturn(FIXED_CLOCK.getZone());
    }

    @AfterEach
    void noExternalInfrastructure() {
        verifyNoInteractions(dataSource, connectionFactory);
    }

    @Test
    void returns200WithConfirmationJsonAndPersistsTimestamp() throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        when(repository.confirmPin(ID, NOW)).thenReturn(1);

        var response = post("{\"pin\":\"000042\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertJson(response);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("id").asText()).isEqualTo(ID);
        assertThat(body.path("estado").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("mensaje").asText()).isEqualTo("PIN confirmado correctamente");
        assertThat(body.has("pin")).isFalse();
        verify(repository).findById(ID);
        verify(repository).confirmPin(ID, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void incorrectPinReturns400AndPersistsRejection() throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        when(repository.rejectPinConfirmation(ID, "El PIN es incorrecto.")).thenReturn(1);

        assertError(post("{\"pin\":\"999999\"}"), 400, "El PIN es incorrecto.");
        verify(repository).findById(ID);
        verify(repository).rejectPinConfirmation(ID, "El PIN es incorrecto.");
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void expiredPinIncludingExactBoundaryReturns400(long secondsUntilExpiry) throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusSeconds(secondsUntilExpiry));
        when(repository.rejectPinConfirmation(ID, "El PIN ha expirado.")).thenReturn(1);

        assertError(post("{\"pin\":\"000042\"}"), 400, "El PIN ha expirado.");
        verify(repository).findById(ID);
        verify(repository).rejectPinConfirmation(ID, "El PIN ha expirado.");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingRequestReturns404() throws Exception {
        when(repository.findById(ID)).thenReturn(Optional.empty());
        assertError(post("{\"pin\":\"000042\"}"), 404, "La solicitud de portabilidad no existe.");
        verifyReadOnly();
    }

    @ParameterizedTest
    @EnumSource(value = PortabilityStatus.class, names = "PIN_GENERATED", mode = EnumSource.Mode.EXCLUDE)
    void incompatibleStateReturns409WithoutUpdates(PortabilityStatus status) throws Exception {
        givenRequest(status, NOW.plusMinutes(1));
        assertError(post("{\"pin\":\"000042\"}"), 409,
                "Solo se puede confirmar una solicitud en estado PIN_GENERATED.");
        verifyReadOnly();
    }

    @Test
    void missingPinDataReturns409WithoutUpdates() throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, null);
        assertError(post("{\"pin\":\"000042\"}"), 409,
                "La solicitud no tiene un PIN generado con sus fechas de vigencia.");
        verifyReadOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"pin\":null}", "{\"pin\":\"\"}",
            "{\"pin\":\"12345\"}", "{\"pin\":\"1234567\"}", "{\"pin\":\"12a456\"}",
            "{\"pin\":\" 123456\"}"})
    void invalidPinFormatReturns400WithoutUpdates(String json) throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        assertError(post(json), 400, "pin debe contener exactamente 6 dígitos.");
        verifyReadOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "[]", "\"123456\"", "{\"pin\":123456}",
            "{\"pin\":true}", "{\"pin\":123.456}", "{} {}"})
    void invalidJsonOrNonStringPinReturns400WithoutRepositoryCalls(String json) throws Exception {
        var response = post(json);
        assertThat(response.statusCode()).isEqualTo(400);
        assertJson(response);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("estado").asText()).isEqualTo("RECHAZADA");
        assertThat(body.path("mensaje").asText()).isNotBlank();
        verifyNoInteractions(repository);
    }

    @Test
    void concurrentChangeReturns409InsteadOfFalseSuccess() throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        when(repository.confirmPin(ID, NOW)).thenReturn(0);
        assertError(post("{\"pin\":\"000042\"}"), 409,
                "La solicitud cambió de estado y ya no se puede confirmar su PIN.");
        verify(repository).findById(ID);
        verify(repository).confirmPin(ID, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void persistenceFailureReturns500WithoutSensitiveDetails() throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        when(repository.confirmPin(ID, NOW)).thenThrow(new DataAccessResourceFailureException("private-db-details"));
        var response = post("{\"pin\":\"000042\"}");
        assertThat(response.statusCode()).isEqualTo(500);
        assertJson(response);
        var body = mapper.readTree(response.body());
        assertThat(body.path("estado").asText()).isEqualTo("ERROR");
        assertThat(body.path("mensaje").asText()).isEqualTo("No se pudo procesar la confirmación del PIN.");
        assertThat(response.body()).doesNotContain("private-db-details", "000042");
    }

    private void givenRequest(PortabilityStatus status, OffsetDateTime expiration) {
        when(repository.findById(ID)).thenReturn(Optional.of(new PortabilityRequest(ID, "+595971234567",
                "12345", "Tigo", "Personal", status, "000042", expiration, 2,
                NOW.minusMinutes(20), NOW.minusMinutes(14), null, null, null)));
    }

    private void verifyReadOnly() {
        verify(repository).findById(ID);
        verifyNoMoreInteractions(repository);
    }

    private HttpResponse<String> post(String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/portabilidad/" + ID + "/pin/confirmar"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String message) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertJson(response);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("estado").asText()).isEqualTo("RECHAZADA");
        assertThat(body.path("mensaje").asText()).isEqualTo(message);
    }

    private void assertJson(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
    }
}
