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
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;
import py.edu.ucom.is2.eapn.repository.PortedNumberRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"camel.springboot.main-run-controller=false", "app.donor-approval.response-timeout-ms=1000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PinConfirmationRouteTest {

    private static final String APPROVAL_PATH = "/operador/portability-approval";
    private static final WireMockServer DONOR = startDonor();

    private static WireMockServer startDonor() {
        var server = new WireMockServer(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"));
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void donorProperties(DynamicPropertyRegistry registry) {
        registry.add("app.wiremock.base-url", DONOR::baseUrl);
    }

    @AfterAll
    static void stopDonor() {
        DONOR.stop();
    }

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
    private PortedNumberRepository portedNumbers;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private DataSource dataSource;
    @MockitoBean
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void setClock() {
        DONOR.resetToDefaultMappings();
        when(clock.instant()).thenReturn(FIXED_CLOCK.instant());
        when(clock.getZone()).thenReturn(FIXED_CLOCK.getZone());
    }

    @AfterEach
    void noExternalInfrastructure() {
        verifyNoInteractions(dataSource, connectionFactory, portedNumbers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tigo", "Personal", "Claro", "Vox"})
    void confirmsThenRequestsApprovalForEachDonor(String donor) throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1), donor, "12345");
        when(repository.confirmPin(ID, NOW)).thenReturn(1);
        when(repository.markPendingDonor(ID)).thenReturn(1);
        when(repository.approveByDonor(ID)).thenReturn(1);

        var response = post("{\"pin\":\"000042\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertJson(response);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("id").asText()).isEqualTo(ID);
        assertThat(body.path("estado").asText()).isEqualTo("APPROVED");
        assertThat(body.path("mensaje").asText()).isEqualTo("Solicitud aprobada por el operador donante");
        assertThat(body.has("pin")).isFalse();
        var order = inOrder(repository);
        order.verify(repository).findById(ID);
        order.verify(repository).confirmPin(ID, NOW);
        order.verify(repository).markPendingDonor(ID);
        order.verify(repository).approveByDonor(ID);
        order.verifyNoMoreInteractions();
        DONOR.verify(1, postRequestedFor(urlEqualTo(APPROVAL_PATH))
                .withHeader("X-Operador-Donante", equalTo(donor))
                .withRequestBody(matchingJsonPath("$.request_id", equalTo(ID)))
                .withRequestBody(matchingJsonPath("$.msisdn", equalTo("+595971234567")))
                .withRequestBody(matchingJsonPath("$.documento_titular", equalTo("12345")))
                .withRequestBody(matchingJsonPath("$.operador_receptor", equalTo(donor.equals("Personal") ? "Tigo" : "Personal"))));
        var sent = mapper.readTree(DONOR.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.size()).isEqualTo(4);
        assertThat(sent.has("pin")).isFalse();
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
        givenRequest(status, expiration, "Tigo", "12345");
    }

    private void givenRequest(PortabilityStatus status, OffsetDateTime expiration, String donor, String document) {
        when(repository.findById(ID)).thenReturn(Optional.of(new PortabilityRequest(ID, "+595971234567",
                document, donor, donor.equals("Personal") ? "Tigo" : "Personal", status, "000042", expiration, 2,
                NOW.minusMinutes(20), NOW.minusMinutes(14), null, null, null)));
    }

    private void verifyReadOnly() {
        verify(repository).findById(ID);
        verifyNoMoreInteractions(repository);
        assertThat(DONOR.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tigo", "Personal", "Claro", "Vox"})
    void controlledRejectionPersistsDonorReason(String donor) throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1), donor, "TEST-REJECTED");
        when(repository.confirmPin(ID, NOW)).thenReturn(1);
        when(repository.markPendingDonor(ID)).thenReturn(1);
        when(repository.rejectByDonor(ID, "Datos del titular no coinciden")).thenReturn(1);

        var response = post("{\"pin\":\"000042\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.path("id").asText()).isEqualTo(ID);
        assertThat(body.path("estado").asText()).isEqualTo("REJECTED");
        assertThat(body.path("mensaje").asText()).isEqualTo("Datos del titular no coinciden");
        var order = inOrder(repository);
        order.verify(repository).findById(ID);
        order.verify(repository).confirmPin(ID, NOW);
        order.verify(repository).markPendingDonor(ID);
        order.verify(repository).rejectByDonor(ID, "Datos del titular no coinciden");
        order.verifyNoMoreInteractions();
        DONOR.verify(1, postRequestedFor(urlEqualTo(APPROVAL_PATH)).withHeader("X-Operador-Donante", equalTo(donor)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 503})
    void httpFailureLeavesPendingDonor(int code) throws Exception {
        allowPendingDonor();
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(APPROVAL_PATH))
                .atPriority(0).willReturn(aResponse().withStatus(code).withBody("private-donor-details")));
        assertTechnicalFailure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "[]",
            "{\"request_id\":\"wrong-id\",\"estado\":\"APPROVED\"}",
            "{\"request_id\":\"REQ-confirm-test\",\"estado\":\"COMPLETED\"}",
            "{\"request_id\":\"REQ-confirm-test\",\"estado\":null}",
            "{\"request_id\":\"REQ-confirm-test\",\"estado\":\"REJECTED\",\"motivo\":null}",
            "{\"request_id\":\"REQ-confirm-test\",\"estado\":\"REJECTED\",\"motivo\":\"  \"}",
            "{\"request_id\":\"REQ-confirm-test\",\"estado\":\"REJECTED\",\"motivo\":123}",
            "{\"request_id\":\"REQ-confirm-test\",\"estado\":\"APPROVED\"} {}"})
    void invalidResponseLeavesPendingDonor(String json) throws Exception {
        allowPendingDonor();
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(APPROVAL_PATH))
                .atPriority(0).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(json)));
        assertTechnicalFailure();
    }

    @Test
    void noResponseLeavesPendingDonorWithoutResending() throws Exception {
        allowPendingDonor();
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(APPROVAL_PATH))
                .atPriority(0).willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        assertTechnicalFailure();
    }

    @Test
    void timeoutLeavesPendingDonorWithoutResending() throws Exception {
        allowPendingDonor();
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(APPROVAL_PATH))
                .atPriority(0).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        assertTechnicalFailure();
    }

    @Test
    void failedPendingTransitionDoesNotCallDonor() throws Exception {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        when(repository.confirmPin(ID, NOW)).thenReturn(1);
        when(repository.markPendingDonor(ID)).thenReturn(0);
        assertThat(post("{\"pin\":\"000042\"}").statusCode()).isEqualTo(500);
        assertThat(DONOR.getAllServeEvents()).isEmpty();
    }

    @Test
    void failedDecisionPersistenceDoesNotReportApproval() throws Exception {
        allowPendingDonor();
        when(repository.approveByDonor(ID)).thenReturn(0);
        assertThat(post("{\"pin\":\"000042\"}").statusCode()).isEqualTo(500);
        verify(repository).findById(ID);
        verify(repository).confirmPin(ID, NOW);
        verify(repository).markPendingDonor(ID);
        verify(repository).approveByDonor(ID);
        verifyNoMoreInteractions(repository);
    }

    private void allowPendingDonor() {
        givenRequest(PortabilityStatus.PIN_GENERATED, NOW.plusMinutes(1));
        when(repository.confirmPin(ID, NOW)).thenReturn(1);
        when(repository.markPendingDonor(ID)).thenReturn(1);
    }

    private void assertTechnicalFailure() throws Exception {
        var response = post("{\"pin\":\"000042\"}");
        assertThat(response.statusCode()).isEqualTo(502);
        assertJson(response);
        var body = mapper.readTree(response.body());
        assertThat(body.path("estado").asText()).isEqualTo("ERROR");
        assertThat(body.path("mensaje").asText()).contains("Solicitud pendiente: " + ID);
        assertThat(response.body()).doesNotContain("private-donor-details", "000042");
        var order = inOrder(repository);
        order.verify(repository).findById(ID);
        order.verify(repository).confirmPin(ID, NOW);
        order.verify(repository).markPendingDonor(ID);
        order.verifyNoMoreInteractions();
        DONOR.verify(1, postRequestedFor(urlEqualTo(APPROVAL_PATH)));
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
