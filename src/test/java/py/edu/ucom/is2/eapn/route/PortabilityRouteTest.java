package py.edu.ucom.is2.eapn.route;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import javax.sql.DataSource;
import jakarta.jms.ConnectionFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"camel.springboot.main-run-controller=false", "app.messaging.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PortabilityRouteTest {

    private static final String NOTIFICATION_PATH = "/operador/pin-notification";
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

    @BeforeEach
    void resetDonor() {
        DONOR.resetToDefaultMappings();
    }

    @AfterAll
    static void stopDonor() {
        DONOR.stop();
    }

    private static final String VALID = """
            {"msisdn":"+595971234567", "documento_titular":"0012345",
             "operador_donante":"tIGO", "operador_receptor":" personal "}
            """;

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private PortabilityRequestRepository repository;
    @MockitoBean
    private DataSource dataSource;
    @MockitoBean
    private ConnectionFactory connectionFactory;

    @ParameterizedTest
    @ValueSource(strings = {"Tigo", "Personal", "Claro", "Vox"})
    void returns201AfterPinNotificationForEachDonor(String donor) throws Exception {
        allowCreationAndPin();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(VALID);
        input.put("operador_donante", donor);
        input.put("operador_receptor", donor.equals("Personal") ? "Tigo" : "Personal");
        var response = post(mapper.writeValueAsString(input));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        var body = mapper.readTree(response.body());
        var saved = ArgumentCaptor.forClass(PortabilityRequest.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().estado()).isEqualTo(PortabilityStatus.CREATED);
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("id").asText()).isEqualTo(saved.getValue().id());
        assertThat(body.path("msisdn").asText()).isEqualTo("+595971234567");
        assertThat(body.path("operador_donante").asText()).isEqualTo(donor);
        assertThat(body.path("operador_receptor").asText()).isEqualTo(donor.equals("Personal") ? "Tigo" : "Personal");
        assertThat(body.path("estado").asText()).isEqualTo("PIN_GENERATED");
        assertThat(body.has("pin")).isFalse();
        assertThat(OffsetDateTime.parse(body.path("fecha_creacion").asText()).toInstant())
                .isEqualTo(saved.getValue().fechaCreacion().toInstant());
        verifyNoInteractions(dataSource, connectionFactory);
        var pin = ArgumentCaptor.forClass(String.class);
        var expiry = ArgumentCaptor.forClass(OffsetDateTime.class);
        var generated = ArgumentCaptor.forClass(OffsetDateTime.class);
        var order = inOrder(repository);
        order.verify(repository).insert(any());
        order.verify(repository).updatePinAndState(eq(saved.getValue().id()), pin.capture(), expiry.capture(),
                generated.capture(), eq(PortabilityStatus.PIN_GENERATED));
        order.verifyNoMoreInteractions();
        assertThat(pin.getValue()).matches("[0-9]{6}");
        assertThat(expiry.getValue()).isEqualTo(generated.getValue().plusMinutes(15));
        DONOR.verify(1, postRequestedFor(urlEqualTo(NOTIFICATION_PATH))
                .withHeader("X-Operador-Donante", equalTo(donor))
                .withRequestBody(matchingJsonPath("$.request_id", equalTo(saved.getValue().id())))
                .withRequestBody(matchingJsonPath("$.msisdn", equalTo("+595971234567")))
                .withRequestBody(matchingJsonPath("$.documento_titular", equalTo("0012345")))
                .withRequestBody(matchingJsonPath("$.pin", equalTo(pin.getValue()))));
        var notification = mapper.readTree(DONOR.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(notification.size()).isEqualTo(4);
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void returnsSpecific400ForBusinessValidation(String field, String value, String message) throws Exception {
        var input = mapper.readTree(VALID).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).put(field, value);
        var response = post(mapper.writeValueAsString(input));
        assertRejection(response, message);
        verifyNoInteractions(repository, dataSource, connectionFactory);
        assertThat(DONOR.getAllServeEvents()).isEmpty();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("msisdn", "0971234567", "msisdn"),
                Arguments.of("documento_titular", "  ", "documento_titular"),
                Arguments.of("operador_donante", "Otro", "operador_donante"),
                Arguments.of("operador_receptor", "Otro", "operador_receptor"),
                Arguments.of("operador_receptor", " TIGO ", "diferentes"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t", "{", "[]", "null", "{}", "\"texto\"",
            "{\"msisdn\":123}", "{\"msisdn\":true}", "{} {}"})
    void rejectsMalformedOrIncompleteJsonWithoutPersistence(String input) throws Exception {
        assertRejection(post(input), "");
        verifyNoInteractions(repository, dataSource, connectionFactory);
        assertThat(DONOR.getAllServeEvents()).isEmpty();
    }

    @Test
    void returns500WithoutExposingDatabaseDetails() throws Exception {
        when(repository.insert(any())).thenThrow(new DataAccessResourceFailureException("private DB details"));
        var response = post(VALID);
        assertThat(response.statusCode()).isEqualTo(500);
        var body = mapper.readTree(response.body());
        assertThat(body.path("estado").asText()).isEqualTo("ERROR");
        assertThat(body.path("mensaje").asText()).isEqualTo("No se pudo registrar la solicitud.");
        assertThat(response.body()).doesNotContain("private DB details");
        assertThat(DONOR.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 503})
    void donorHttpErrorReturns502AndRecordsReasonWithoutAdvancingState(int code) throws Exception {
        allowCreationAndPin();
        when(repository.updateFailureReason(anyString(), anyString())).thenReturn(1);
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(NOTIFICATION_PATH))
                .atPriority(1).willReturn(aResponse().withStatus(code).withBody("private-donor-body")));

        var response = post(VALID);
        assertNotificationFailure(response);
        var id = ArgumentCaptor.forClass(String.class);
        var order = inOrder(repository);
        order.verify(repository).insert(any());
        order.verify(repository).updatePinAndState(id.capture(), anyString(), any(), any(), eq(PortabilityStatus.PIN_GENERATED));
        order.verify(repository).updateFailureReason(id.getValue(), "El operador donante devolvió HTTP " + code + ".");
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}",
            "{\"request_id\":\"wrong-id\",\"estado\":\"PIN_ENVIADO\"}",
            "{\"request_id\":\"{{jsonPath request.body '$.request_id'}}\",\"estado\":\"RECHAZADO\"}"})
    void invalidConfirmationDoesNotBecomeSuccessOrClientValidationError(String confirmation) throws Exception {
        allowCreationAndPin();
        when(repository.updateFailureReason(anyString(), anyString())).thenReturn(1);
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(NOTIFICATION_PATH))
                .atPriority(1).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(confirmation).withTransformers("response-template")));

        assertNotificationFailure(post(VALID));
        verify(repository).insert(any());
        verify(repository).updatePinAndState(anyString(), anyString(), any(), any(), eq(PortabilityStatus.PIN_GENERATED));
        verify(repository).updateFailureReason(anyString(), anyString());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void doesNotContactDonorIfPinPersistenceFails() throws Exception {
        when(repository.insert(any())).thenReturn(1);
        // updatePinAndState devuelve 0: no se confirmó la persistencia del PIN.
        assertThat(post(VALID).statusCode()).isEqualTo(500);
        assertThat(DONOR.getAllServeEvents()).isEmpty();
    }

    @Test
    void connectionFailureReturns502WithoutAutomaticResend() throws Exception {
        allowCreationAndPin();
        when(repository.updateFailureReason(anyString(), anyString())).thenReturn(1);
        DONOR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(NOTIFICATION_PATH))
                .atPriority(1).willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));

        assertNotificationFailure(post(VALID));
        verify(repository).insert(any());
        verify(repository).updatePinAndState(anyString(), anyString(), any(), any(), eq(PortabilityStatus.PIN_GENERATED));
        verify(repository).updateFailureReason(anyString(), eq("No se obtuvo una confirmación válida del operador donante."));
        verifyNoMoreInteractions(repository);
    }

    private void allowCreationAndPin() {
        when(repository.insert(any())).thenReturn(1);
        when(repository.updatePinAndState(anyString(), anyString(), any(), any(),
                eq(PortabilityStatus.PIN_GENERATED))).thenReturn(1);
    }

    private void assertNotificationFailure(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(502);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("estado").asText()).isEqualTo("ERROR");
        assertThat(body.path("mensaje").asText()).contains("No se pudo confirmar el envío del PIN.");
        assertThat(response.body()).doesNotContain("private-donor-body", "\"pin\"");
        DONOR.verify(1, postRequestedFor(urlEqualTo(NOTIFICATION_PATH)));
        verifyNoInteractions(dataSource, connectionFactory);
    }

    private HttpResponse<String> post(String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/portabilidad"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertRejection(HttpResponse<String> response, String message) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("estado").asText()).isEqualTo("RECHAZADA");
        assertThat(body.path("mensaje").asText()).isNotBlank().contains(message);
    }
}
