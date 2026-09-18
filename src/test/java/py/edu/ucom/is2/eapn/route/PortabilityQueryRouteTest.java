package py.edu.ucom.is2.eapn.route;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import jakarta.jms.ConnectionFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"camel.springboot.main-run-controller=false", "app.messaging.enabled=false"})
class PortabilityQueryRouteTest {
    private static final String ID = "REQ-CONSULTA-001";
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-09-18T10:00:00-03:00");
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean PortabilityRequestRepository repository;
    @MockitoBean DataSource dataSource;
    @MockitoBean ConnectionFactory connectionFactory;

    @ParameterizedTest @EnumSource(PortabilityStatus.class)
    void returnsCurrentStateAndOnlyPublicMilestones(PortabilityStatus state) throws Exception {
        var generated = state == PortabilityStatus.CREATED ? null : CREATED.plusSeconds(1);
        var confirmed = switch (state) {
            case CONFIRMED, PENDING_DONOR, APPROVED, REJECTED, COMPLETED -> CREATED.plusMinutes(1);
            default -> null;
        };
        var completed = state == PortabilityStatus.COMPLETED ? CREATED.plusMinutes(2) : null;
        String reason = state == PortabilityStatus.REJECTED ? "Datos del titular no coinciden" : null;
        var request = new PortabilityRequest(ID, "+595971234567", "DOCUMENTO-PRIVADO", "Tigo", "Personal", state,
                "000042", CREATED.plusMinutes(15), 1, CREATED, generated, confirmed, completed, reason);
        when(repository.findById(ID)).thenReturn(Optional.of(request));

        var response = get();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        var json = mapper.readTree(response.body());
        var fields = new ArrayList<String>();
        json.fieldNames().forEachRemaining(fields::add);
        var expected = new ArrayList<>(List.of("id", "msisdn", "operador_donante", "operador_receptor", "estado",
                "fecha_creacion", "fecha_pin_generado", "fecha_pin_confirmado", "fecha_completada"));
        if (reason != null) expected.add("motivo_rechazo");
        assertThat(fields).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(json.path("id").asText()).isEqualTo(ID);
        assertThat(json.path("msisdn").asText()).isEqualTo(request.msisdn());
        assertThat(json.path("operador_donante").asText()).isEqualTo("Tigo");
        assertThat(json.path("operador_receptor").asText()).isEqualTo("Personal");
        assertThat(json.path("estado").asText()).isEqualTo(state.name());
        assertThat(OffsetDateTime.parse(json.path("fecha_creacion").asText()).toInstant()).isEqualTo(CREATED.toInstant());
        for (String field : List.of("fecha_pin_generado", "fecha_pin_confirmado", "fecha_completada")) {
            OffsetDateTime date = switch (field) {
                case "fecha_pin_generado" -> generated;
                case "fecha_pin_confirmado" -> confirmed;
                default -> completed;
            };
            if (date == null) assertThat(json.path(field).isNull()).isTrue();
            else assertThat(OffsetDateTime.parse(json.path(field).asText()).toInstant()).isEqualTo(date.toInstant());
        }
        if (reason != null) assertThat(json.path("motivo_rechazo").asText()).isEqualTo(reason);
        assertThat(response.body()).doesNotContain("\"pin\"", "pin_expiracion", "000042", "DOCUMENTO-PRIVADO",
                "JMS", "operation", "payload", "intentos_confirmacion");
        assertThat(response.headers().firstValue("JMSCorrelationID")).isEmpty();
        verify(repository).findById(ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(dataSource, connectionFactory);
    }

    @Test void missingRequestReturns404() throws Exception {
        when(repository.findById(ID)).thenReturn(Optional.empty());
        var response = get();
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree(
                "{\"estado\":\"RECHAZADA\",\"mensaje\":\"La solicitud de portabilidad no existe.\"}"));
        verify(repository).findById(ID);
        verifyNoMoreInteractions(repository);
    }

    @Test void databaseFailureReturnsSafe500() throws Exception {
        when(repository.findById(ID)).thenThrow(new DataAccessResourceFailureException("JMS password=secret PIN=000042"));
        var response = get();
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree(
                "{\"estado\":\"ERROR\",\"mensaje\":\"No se pudo consultar la solicitud.\"}"));
        assertThat(response.headers().firstValue("JMSCorrelationID")).isEmpty();
        verify(repository).findById(ID);
        verifyNoMoreInteractions(repository);
    }

    @Test void technicalPinDeliveryReasonIsNotPublishedAsBusinessRejection() throws Exception {
        when(repository.findById(ID)).thenReturn(Optional.of(new PortabilityRequest(ID, "+595971234567", "12345",
                "Tigo", "Personal", PortabilityStatus.PIN_GENERATED, "000042", CREATED.plusMinutes(15), 0,
                CREATED, CREATED, null, null, "Fallo técnico JMS eapn.error")));
        var response = get();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).has("motivo_rechazo")).isFalse();
        assertThat(response.body()).doesNotContain("JMS", "eapn.error", "000042");
    }

    private HttpResponse<String> get() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/portabilidad/" + ID))
                .header("JMSCorrelationID", "PRIVATE-INTERNAL-HEADER").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
