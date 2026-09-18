package py.edu.ucom.is2.eapn.messaging;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSConsumer;
import jakarta.jms.TextMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;
import py.edu.ucom.is2.eapn.repository.ProcessedMessageRepository;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Artemis real dentro de la JVM, JDBC real con H2 y WireMock local; no usa Docker. */
@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "camel.springboot.main-run-controller=false", "app.messaging.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:messaging;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:messaging-schema.sql",
        "app.donor-approval.response-timeout-ms=300", "app.receiver-notification.response-timeout-ms=300"})
@Import(MessagingIntegrationTest.Infrastructure.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessagingIntegrationTest {
    static final WireMockServer OPERATORS = startOperators();
    static final String MSISDN = "+595971234567";
    static final String PIN = "000042";
    static final String ID = "REQ-MESSAGING-001";
    static final Instant NOW = Instant.parse("2026-09-18T13:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean(initMethod = "start", destroyMethod = "stop")
        ActiveMQServer broker() throws Exception {
            return ActiveMQServers.newActiveMQServer(new ConfigurationImpl()
                    .setPersistenceEnabled(false).setSecurityEnabled(false)
                    .addAcceptorConfiguration("in-vm", "vm://71"));
        }
        @Bean(destroyMethod = "close")
        ActiveMQConnectionFactory connectionFactory(ActiveMQServer broker) {
            return new ActiveMQConnectionFactory("vm://71");
        }
        @Bean @Primary
        Clock testClock() { return Clock.fixed(NOW, ZoneId.of("America/Asuncion")); }
    }

    static WireMockServer startOperators() {
        var server = new WireMockServer(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"));
        server.start();
        return server;
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.wiremock.base-url", OPERATORS::baseUrl);
    }
    @AfterAll static void stopOperators() { OPERATORS.stop(); }

    @Autowired ObjectMapper mapper;
    @Autowired ProducerTemplate producer;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired PortabilityRequestRepository requests;
    @MockitoSpyBean ProcessedMessageRepository processed;
    @MockitoSpyBean OperatorExecutor executor;
    @LocalServerPort int port;
    JMSContext observer;
    JMSConsumer errors;
    JMSConsumer events;

    @BeforeEach void reset() {
        OPERATORS.resetToDefaultMappings();
        jdbc.update("DELETE FROM processed_message");
        jdbc.update("DELETE FROM ported_number");
        jdbc.update("DELETE FROM portability_request");
        observer = connectionFactory.createContext();
        errors = observer.createConsumer(observer.createQueue("eapn.error"));
        while (errors.receiveNoWait() != null) { /* limpiar el test anterior */ }
        events = observer.createConsumer(observer.createTopic("eapn.state.events"));
    }
    @AfterEach void close() { observer.close(); }

    @Test void realRestFlowPublishesAllSuccessStatesAndProcessesThreeJmsOperations() throws Exception {
        var created = create("12345");
        String id = created.path("id").asText();
        var request = requests.findById(id).orElseThrow();
        var response = postApi("/portabilidad/" + id + "/pin/confirmar", Map.of("pin", request.pin()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("estado").asText()).isEqualTo("COMPLETED");
        assertThat(response.body()).doesNotContain("\"pin\"", request.pin());
        assertThat(requests.findById(id).orElseThrow().estado()).isEqualTo(PortabilityStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ported_number", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE reply IS NOT NULL", Integer.class)).isEqualTo(3);
        assertEvents(id, "CREATED", "PIN_GENERATED", "CONFIRMED", "APPROVED", "COMPLETED");
        assertThat(errors.receive(300)).isNull();
    }

    @Test void businessRejectionIsNotDeadLetteredAndPublishesRejectedEvent() throws Exception {
        var created = create("TEST-REJECTED");
        String id = created.path("id").asText();
        var response = postApi("/portabilidad/" + id + "/pin/confirmar", Map.of("pin", requests.findById(id).orElseThrow().pin()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("estado").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ported_number", Integer.class)).isZero();
        assertEvents(id, "CREATED", "PIN_GENERATED", "CONFIRMED", "REJECTED");
        assertThat(errors.receive(300)).isNull();
    }

    @Test void incorrectPinPublishesRejectedWithoutCallingApproval() throws Exception {
        var created = create("12345");
        String id = created.path("id").asText();
        String actualPin = requests.findById(id).orElseThrow().pin();
        var response = postApi("/portabilidad/" + id + "/pin/confirmar", Map.of("pin", actualPin.equals(PIN) ? "999999" : PIN));
        assertThat(response.statusCode()).isEqualTo(400);
        assertEvents(id, "CREATED", "PIN_GENERATED", "REJECTED");
        OPERATORS.verify(0, postRequestedFor(urlEqualTo("/operador/portability-approval")));
        assertThat(errors.receive(300)).isNull();
    }

    static Stream<Arguments> operators() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of("Tigo", "Personal", "Claro", "Vox")
                .map(operator -> Arguments.of(operation, operator)));
    }
    @ParameterizedTest @MethodSource("operators")
    void jmsConsumerRoutesEachOperatorWithCorrelationAndSafePayload(Operation operation, String operator) throws Exception {
        var command = command(operation, operator);
        var reply = send(command);
        assertThat(reply.successful()).isTrue();
        String roleHeader = operation == Operation.RECEIVER_NOTIFICATION ? "X-Operador-Receptor" : "X-Operador-Donante";
        OPERATORS.verify(1, postRequestedFor(urlEqualTo(path(operation))).withHeader(roleHeader, equalTo(operator))
                .withRequestBody(matchingJsonPath("$.request_id", equalTo(ID))));
        String json = mapper.writeValueAsString(command);
        if (operation != Operation.PIN_NOTIFICATION) assertThat(json).doesNotContain("\"pin\"", PIN);
        assertThat(mapper.writeValueAsString(reply)).doesNotContain("\"pin\"", PIN);
        assertThat(errors.receive(100)).isNull();
    }

    @Test void duplicateUsesPersistentReplyWithoutRepeatingHttpEvenWithNewRepositoryInstance() throws Exception {
        var command = command(Operation.PIN_NOTIFICATION, "Tigo");
        var first = send(command);
        var second = send(command);
        assertThat(second).isEqualTo(first);
        OPERATORS.verify(1, postRequestedFor(urlEqualTo(path(command.operation()))));
        var newRepository = new ProcessedMessageRepository(jdbc);
        assertThat(newRepository.claim(command.operation().name(), ID)).isFalse();
        assertThat(newRepository.findReply(command.operation().name(), ID)).isPresent();
    }

    @Test void sameRequestIdCanPerformDifferentOperations() throws Exception {
        for (Operation operation : Operation.values()) assertThat(send(command(operation, "Tigo")).successful()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message", Integer.class)).isEqualTo(3);
    }

    @Test void transientHttpFailureRetriesThenSucceedsWithoutDlc() throws Exception {
        OPERATORS.stubFor(post(urlEqualTo("/operador/portability-approval")).atPriority(0)
                .inScenario("retry").whenScenarioStateIs("Started").willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
        assertThat(send(command(Operation.DONOR_APPROVAL, "Tigo")).successful()).isTrue();
        OPERATORS.verify(2, postRequestedFor(urlEqualTo("/operador/portability-approval")));
        assertThat(errors.receive(300)).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"http", "json", "correlation", "state", "timeout"})
    void exhaustedTechnicalFailureRetriesThreeTimesAndProducesSafeError(String failure) throws Exception {
        var response = aResponse().withHeader("Content-Type", "application/json");
        switch (failure) {
            case "http" -> response.withStatus(503).withBody("PIN=" + PIN + "; password=secret");
            case "json" -> response.withBody("not-json PIN=" + PIN);
            case "correlation" -> response.withBody("{\"request_id\":\"OTHER\",\"estado\":\"APPROVED\"}");
            case "state" -> response.withBody("{\"request_id\":\"" + ID + "\",\"estado\":\"UNKNOWN\"}");
            case "timeout" -> response.withFixedDelay(800).withBody("{}");
        }
        OPERATORS.stubFor(post(urlEqualTo("/operador/portability-approval")).atPriority(0).willReturn(response));
        assertThat(send(command(Operation.DONOR_APPROVAL, "Tigo")).successful()).isFalse();
        OPERATORS.verify(3, postRequestedFor(urlEqualTo("/operador/portability-approval")));
        assertError(ID, "eapn.donor.approval");
        assertThat(processed.findReply(Operation.DONOR_APPROVAL.name(), ID)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message", Integer.class)).isZero();
    }

    @Test void unexpectedProcessorFailureGoesToDlcAndDoesNotLeakExceptionMessage() throws Exception {
        doThrow(new IllegalStateException("PIN=" + PIN + " password=secret")).when(executor).execute(any());
        assertThat(send(command(Operation.PIN_NOTIFICATION, "Tigo")).successful()).isFalse();
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.times(3)).execute(any());
        assertError(ID, "eapn.pin.notification");
    }

    @Test void corruptJmsMessageGoesToErrorQueueWithoutOriginalBody() throws Exception {
        producer.sendBodyAndHeader("jms:queue:eapn.pin.notification", "bad-json PIN=" + PIN, "JMSCorrelationID", ID);
        assertError(ID, "eapn.pin.notification");
        OPERATORS.verify(0, postRequestedFor(urlEqualTo("/operador/pin-notification")));
    }

    @Test void exhaustedApprovalLeavesBusinessPendingDonor() throws Exception {
        var created = create("12345");
        String id = created.path("id").asText();
        OPERATORS.stubFor(post(urlEqualTo("/operador/portability-approval")).atPriority(0).willReturn(aResponse().withStatus(500)));
        var response = postApi("/portabilidad/" + id + "/pin/confirmar", Map.of("pin", requests.findById(id).orElseThrow().pin()));
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(requests.findById(id).orElseThrow().estado()).isEqualTo(PortabilityStatus.PENDING_DONOR);
        assertError(id, "eapn.donor.approval");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ported_number", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"12345", "TEST-REJECTED"})
    void exhaustedReceiverNotificationPreservesFinalBusinessState(String document) throws Exception {
        var created = create(document);
        String id = created.path("id").asText();
        OPERATORS.stubFor(post(urlEqualTo("/receptor/portability-result")).atPriority(0).willReturn(aResponse().withStatus(500)));
        var response = postApi("/portabilidad/" + id + "/pin/confirmar", Map.of("pin", requests.findById(id).orElseThrow().pin()));
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(requests.findById(id).orElseThrow().estado()).isEqualTo(
                document.equals("12345") ? PortabilityStatus.COMPLETED : PortabilityStatus.REJECTED);
        assertError(id, "eapn.receiver.notification");
    }

    @Test void concurrentDuplicateDoesNotRepeatOrReleaseAnotherConsumersClaim() throws Exception {
        assertThat(processed.claim(Operation.PIN_NOTIFICATION.name(), ID)).isTrue();
        assertThat(send(command(Operation.PIN_NOTIFICATION, "Tigo")).successful()).isFalse();
        OPERATORS.verify(0, postRequestedFor(urlEqualTo("/operador/pin-notification")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message", Integer.class)).isEqualTo(1);
        assertError(ID, "eapn.pin.notification");
    }

    @Test void failedCacheWriteRetriesJdbcWithoutRepeatingSuccessfulHttp() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Temporary JDBC error"))
                .doCallRealMethod().when(processed).complete(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThat(send(command(Operation.PIN_NOTIFICATION, "Tigo")).successful()).isTrue();
        OPERATORS.verify(1, postRequestedFor(urlEqualTo("/operador/pin-notification")));
        assertThat(errors.receive(200)).isNull();
    }

    @Test void invalidOperatorIsRejectedByContentRouterAndDeadLettered() throws Exception {
        assertThat(send(command(Operation.DONOR_APPROVAL, "Unknown")).successful()).isFalse();
        OPERATORS.verify(0, postRequestedFor(urlEqualTo("/operador/portability-approval")));
        assertError(ID, "eapn.donor.approval");
    }

    @Test void correlationMismatchNeverInvokesOperator() throws Exception {
        producer.sendBodyAndHeader("jms:queue:eapn.donor.approval",
                mapper.writeValueAsString(command(Operation.DONOR_APPROVAL, "Tigo")), "JMSCorrelationID", "OTHER");
        assertError("OTHER", "eapn.donor.approval");
        OPERATORS.verify(0, postRequestedFor(urlEqualTo("/operador/portability-approval")));
    }

    @Test void exhaustedPinDeliveryPreservesGeneratedStateAndCanBeExplicitlyReplayed() throws Exception {
        OPERATORS.stubFor(post(urlEqualTo("/operador/pin-notification")).atPriority(0).willReturn(aResponse().withStatus(503)));
        var response = postApi("/portabilidad", Map.of("msisdn", MSISDN, "documento_titular", "12345",
                "operador_donante", "Tigo", "operador_receptor", "Personal"));
        assertThat(response.statusCode()).isEqualTo(502);
        String id = jdbc.queryForObject("SELECT id FROM portability_request", String.class);
        var request = requests.findById(id).orElseThrow();
        assertThat(request.estado()).isEqualTo(PortabilityStatus.PIN_GENERATED);
        assertError(id, "eapn.pin.notification");
        OPERATORS.resetToDefaultMappings();
        var payload = mapper.createObjectNode().put("request_id", id).put("msisdn", MSISDN)
                .put("documento_titular", request.documentoTitular()).put("pin", request.pin());
        assertThat(send(new OperatorCommand(id, MSISDN, Operation.PIN_NOTIFICATION, "Tigo", payload)).successful()).isTrue();
        assertThat(requests.findById(id).orElseThrow().pin()).isEqualTo(request.pin());
    }

    private OperatorCommand command(Operation operation, String operator) {
        var payload = mapper.createObjectNode().put("request_id", ID).put("msisdn", MSISDN);
        switch (operation) {
            case PIN_NOTIFICATION -> payload.put("documento_titular", "12345").put("pin", PIN);
            case DONOR_APPROVAL -> payload.put("documento_titular", "12345").put("operador_receptor", "Personal");
            case RECEIVER_NOTIFICATION -> payload.put("estado", "COMPLETED").put("operador_donante", "Personal")
                    .put("operador_receptor", operator).put("fecha_finalizacion", OffsetDateTime.ofInstant(NOW, ZoneId.of("America/Asuncion")).toString())
                    .putNull("motivo");
        }
        return new OperatorCommand(ID, MSISDN, operation, operator, payload);
    }
    private OperatorReply send(OperatorCommand command) throws Exception {
        var exchange = producer.request("jms:queue:" + command.operation().queue + "?requestTimeout=10000", message -> {
            message.getMessage().setBody(mapper.writeValueAsString(command));
            message.getMessage().setHeader("JMSCorrelationID", command.requestId());
            message.getMessage().setHeader("operation", command.operation().name());
        });
        assertThat(exchange.isFailed()).isFalse();
        assertThat(exchange.getMessage().getHeader("JMSCorrelationID", String.class)).isEqualTo(command.requestId());
        var reply = mapper.readValue(exchange.getMessage().getBody(String.class), OperatorReply.class);
        assertThat(reply.requestId()).isEqualTo(command.requestId());
        return reply;
    }
    private String path(Operation operation) {
        return switch (operation) {
            case PIN_NOTIFICATION -> "/operador/pin-notification";
            case DONOR_APPROVAL -> "/operador/portability-approval";
            case RECEIVER_NOTIFICATION -> "/receptor/portability-result";
        };
    }
    private JsonNode create(String document) throws Exception {
        var response = postApi("/portabilidad", Map.of("msisdn", MSISDN, "documento_titular", document,
                "operador_donante", "Tigo", "operador_receptor", "Personal"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return mapper.readTree(response.body());
    }
    private HttpResponse<String> postApi(String path, Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertEvents(String id, String... states) throws Exception {
        List<String> received = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            var message = (TextMessage) events.receive(5000);
            assertThat(message).isNotNull();
            assertThat(message.getJMSCorrelationID()).isEqualTo(id);
            String json = message.getText();
            assertThat(json).doesNotContain("\"pin\"", "documento", "password");
            var event = mapper.readTree(json);
            assertThat(event.path("request_id").asText()).isEqualTo(id);
            assertThat(event.path("msisdn").asText()).isEqualTo(MSISDN);
            assertThat(Instant.parse(event.path("timestamp").asText())).isEqualTo(NOW);
            assertThat(event.path("operation").asText()).isEqualTo("STATE_CHANGED");
            received.add(event.path("estado").asText());
        }
        // WireTap usa tareas independientes: no imponer orden de llegada entre estados.
        assertThat(received).containsExactlyInAnyOrder(states);
    }
    private void assertError(String id, String origin) throws Exception {
        var message = (TextMessage) errors.receive(5000);
        assertThat(message).isNotNull();
        assertThat(message.getJMSCorrelationID()).isEqualTo(id);
        assertThat(message.getStringProperty("operation")).isEqualTo("TECHNICAL_ERROR");
        String json = message.getText();
        assertThat(json).doesNotContain(PIN, "password", "secret", "documento", "\"pin\"");
        var error = mapper.readTree(json);
        assertThat(error.path("request_id").asText()).isEqualTo(id);
        assertThat(error.path("origin").asText()).isEqualTo(origin);
        assertThat(error.path("exceptionType").asText()).isNotBlank();
        assertThat(error.path("timestamp").asText()).isNotBlank();
        assertThat(errors.receive(100)).isNull();
    }
}
