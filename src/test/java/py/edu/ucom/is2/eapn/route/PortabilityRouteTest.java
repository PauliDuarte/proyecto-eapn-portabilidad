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
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
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

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "camel.springboot.main-run-controller=false")
class PortabilityRouteTest {

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

    @Test
    void returns201WithSnakeCaseJsonAndPersistsCreatedRequest() throws Exception {
        when(repository.insert(any())).thenReturn(1);
        var response = post(VALID);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        var body = mapper.readTree(response.body());
        var saved = ArgumentCaptor.forClass(PortabilityRequest.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().estado()).isEqualTo(PortabilityStatus.CREATED);
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("id").asText()).isEqualTo(saved.getValue().id());
        assertThat(body.path("msisdn").asText()).isEqualTo("+595971234567");
        assertThat(body.path("operador_donante").asText()).isEqualTo("Tigo");
        assertThat(body.path("operador_receptor").asText()).isEqualTo("Personal");
        assertThat(body.path("estado").asText()).isEqualTo("CREATED");
        assertThat(OffsetDateTime.parse(body.path("fecha_creacion").asText()).toInstant())
                .isEqualTo(saved.getValue().fechaCreacion().toInstant());
        verifyNoInteractions(dataSource, connectionFactory);
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void returnsSpecific400ForBusinessValidation(String field, String value, String message) throws Exception {
        var input = mapper.readTree(VALID).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).put(field, value);
        var response = post(mapper.writeValueAsString(input));
        assertRejection(response, message);
        verifyNoInteractions(repository, dataSource, connectionFactory);
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
