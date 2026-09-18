package py.edu.ucom.is2.eapn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortabilityRequestRepositoryTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-09-17T10:00:00-03:00");
    private static final OffsetDateTime GENERATED = CREATED.plusMinutes(1);
    private static final OffsetDateTime EXPIRES = CREATED.plusMinutes(6);
    private static final OffsetDateTime CONFIRMED = CREATED.plusMinutes(2);
    private static final OffsetDateTime COMPLETED = CREATED.plusHours(1);

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private ResultSet rs;

    private PortabilityRequestRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PortabilityRequestRepository(jdbc);
    }

    @Test
    void insertsEveryFieldWithExplicitTimestampTypes() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        assertThat(repository.insert(request())).isEqualTo(1);

        var sql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("INSERT INTO portability_request", "documento_titular",
                "operador_donante", "operador_receptor", "intentos_confirmacion", "fecha_creacion");
        assertThat(params.getValue().getValues()).hasSize(14)
                .containsEntry("id", "req-1")
                .containsEntry("msisdn", "595981123456")
                .containsEntry("documentoTitular", "0012345")
                .containsEntry("operadorDonante", "DONANTE")
                .containsEntry("operadorReceptor", "RECEPTOR")
                .containsEntry("estado", "REJECTED")
                .containsEntry("pin", "001234")
                .containsEntry("pinExpiracion", EXPIRES)
                .containsEntry("intentosConfirmacion", 2)
                .containsEntry("fechaCreacion", CREATED)
                .containsEntry("fechaPinGenerado", GENERATED)
                .containsEntry("fechaPinConfirmado", CONFIRMED)
                .containsEntry("fechaCompletada", COMPLETED)
                .containsEntry("motivoRechazo", "Motivo de prueba");
        for (String name : List.of("pinExpiracion", "fechaCreacion", "fechaPinGenerado",
                "fechaPinConfirmado", "fechaCompletada")) {
            assertThat(params.getValue().getSqlType(name)).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }

    @Test
    void insertsInitialRequestWithNullableFields() {
        var initial = new PortabilityRequest("req-2", "595981123456", "0012345", "DONANTE", "RECEPTOR",
                PortabilityStatus.CREATED, null, null, 0, CREATED, null, null, null, null);
        repository.insert(initial);

        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValues()).containsEntry("estado", "CREATED")
                .containsEntry("intentosConfirmacion", 0);
        for (String name : List.of("pin", "pinExpiracion", "fechaPinGenerado", "fechaPinConfirmado",
                "fechaCompletada", "motivoRechazo")) {
            assertThat(params.getValue().hasValue(name)).isTrue();
            assertThat(params.getValue().getValue(name)).isNull();
        }
    }

    @Test
    void findsByIdUsingBoundParameterAndRequestMapper() {
        when(jdbc.query(eq("SELECT * FROM portability_request WHERE id = :id"),
                any(MapSqlParameterSource.class), same(PortabilityRequestRepository.ROW_MAPPER)))
                .thenReturn(List.of(request()));

        assertThat(repository.findById("req-1")).contains(request());

        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(anyString(), params.capture(), same(PortabilityRequestRepository.ROW_MAPPER));
        assertThat(params.getValue().getValues()).containsOnlyKeys("id").containsEntry("id", "req-1");
    }

    @Test
    void returnsEmptyWhenIdDoesNotExist() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                same(PortabilityRequestRepository.ROW_MAPPER))).thenReturn(List.of());
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    void updatesOnlyStateForSpecifiedId() {
        repository.updateState("req-1", PortabilityStatus.PENDING_DONOR);
        var params = captureUpdate("UPDATE portability_request SET estado = :estado WHERE id = :id");
        assertThat(params.getValues()).hasSize(2).containsEntry("estado", "PENDING_DONOR");
    }

    @Test
    void updatesProvidedPinAndDatesWithoutChangingState() {
        repository.updatePin("req-1", "001234", EXPIRES, GENERATED);
        var params = captureUpdate("""
                UPDATE portability_request
                SET pin = :pin, pin_expiracion = :pinExpiracion, fecha_pin_generado = :fechaPinGenerado
                WHERE id = :id
                """);
        assertThat(params.getValues()).hasSize(4).containsEntry("pin", "001234")
                .containsEntry("pinExpiracion", EXPIRES).containsEntry("fechaPinGenerado", GENERATED);
        assertThat(params.getSqlType("pinExpiracion")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(params.getSqlType("fechaPinGenerado")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    void updatesOnlyConfirmationDate() {
        repository.updateConfirmationDate("req-1", CONFIRMED);
        var params = captureUpdate("""
                UPDATE portability_request SET fecha_pin_confirmado = :fechaPinConfirmado WHERE id = :id
                """);
        assertThat(params.getValues()).hasSize(2).containsEntry("fechaPinConfirmado", CONFIRMED);
        assertThat(params.getSqlType("fechaPinConfirmado")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    void updatesCompletionWithNullRejectionReason() {
        repository.updateCompletion("req-1", COMPLETED, null);
        var params = captureCompletion();
        assertThat(params.getValues()).hasSize(3).containsEntry("fechaCompletada", COMPLETED)
                .containsEntry("motivoRechazo", null);
        assertThat(params.getSqlType("motivoRechazo")).isEqualTo(Types.VARCHAR);
    }

    @Test
    void storesRejectionWithoutInventingCompletionDate() {
        repository.updateCompletion("req-1", null, "Rechazado por donante");
        var params = captureCompletion();
        assertThat(params.getValues()).hasSize(3).containsEntry("fechaCompletada", null)
                .containsEntry("motivoRechazo", "Rechazado por donante");
        assertThat(params.getSqlType("fechaCompletada")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    void exposesAffectedRowsIncludingMissingRequests() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1, 0);
        assertThat(repository.updateState("req-1", PortabilityStatus.CONFIRMED)).isEqualTo(1);
        assertThat(repository.updateState("missing", PortabilityStatus.CONFIRMED)).isZero();
    }

    @Test
    void propagatesDuplicateKeyWithoutOverwritingRequest() {
        var failure = new DuplicateKeyException("Duplicated id");
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);
        assertThatThrownBy(() -> repository.insert(request())).isSameAs(failure);
    }

    @Test
    void doesNotTreatDatabaseFailureAsMissingRequest() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                same(PortabilityRequestRepository.ROW_MAPPER))).thenThrow(failure);
        assertThatThrownBy(() -> repository.findById("req-1")).isSameAs(failure);
    }

    @Test
    void mapsEveryColumnIncludingLeadingZerosAndDates() throws SQLException {
        when(rs.getString("id")).thenReturn("req-1");
        when(rs.getString("msisdn")).thenReturn("595981123456");
        when(rs.getString("documento_titular")).thenReturn("0012345");
        when(rs.getString("operador_donante")).thenReturn("DONANTE");
        when(rs.getString("operador_receptor")).thenReturn("RECEPTOR");
        when(rs.getString("estado")).thenReturn("REJECTED");
        when(rs.getString("pin")).thenReturn("001234");
        when(rs.getObject("pin_expiracion", OffsetDateTime.class)).thenReturn(EXPIRES);
        when(rs.getInt("intentos_confirmacion")).thenReturn(2);
        when(rs.getObject("fecha_creacion", OffsetDateTime.class)).thenReturn(CREATED);
        when(rs.getObject("fecha_pin_generado", OffsetDateTime.class)).thenReturn(GENERATED);
        when(rs.getObject("fecha_pin_confirmado", OffsetDateTime.class)).thenReturn(CONFIRMED);
        when(rs.getObject("fecha_completada", OffsetDateTime.class)).thenReturn(COMPLETED);
        when(rs.getString("motivo_rechazo")).thenReturn("Motivo de prueba");

        assertThat(PortabilityRequestRepository.ROW_MAPPER.mapRow(rs, 0)).isEqualTo(request());
    }

    @ParameterizedTest
    @EnumSource(PortabilityStatus.class)
    void mapsAllStatesAndNullableColumns(PortabilityStatus status) throws SQLException {
        when(rs.getString("id")).thenReturn("req-2");
        when(rs.getString("msisdn")).thenReturn("595981123456");
        when(rs.getString("documento_titular")).thenReturn("0012345");
        when(rs.getString("operador_donante")).thenReturn("DONANTE");
        when(rs.getString("operador_receptor")).thenReturn("RECEPTOR");
        when(rs.getString("estado")).thenReturn(status.name());
        when(rs.getString("pin")).thenReturn(null);
        when(rs.getString("motivo_rechazo")).thenReturn(null);
        when(rs.getObject("fecha_creacion", OffsetDateTime.class)).thenReturn(CREATED);
        when(rs.getObject("pin_expiracion", OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject("fecha_pin_generado", OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject("fecha_pin_confirmado", OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject("fecha_completada", OffsetDateTime.class)).thenReturn(null);
        var mapped = PortabilityRequestRepository.ROW_MAPPER.mapRow(rs, 0);

        assertThat(mapped.fechaCreacion()).isEqualTo(CREATED);
        assertThat(mapped.estado()).isEqualTo(status);
        assertThat(mapped.pin()).isNull();
        assertThat(mapped.pinExpiracion()).isNull();
        assertThat(mapped.fechaPinGenerado()).isNull();
        assertThat(mapped.fechaPinConfirmado()).isNull();
        assertThat(mapped.fechaCompletada()).isNull();
        assertThat(mapped.motivoRechazo()).isNull();
    }

    private MapSqlParameterSource captureCompletion() {
        return captureUpdate("""
                UPDATE portability_request
                SET fecha_completada = :fechaCompletada, motivo_rechazo = :motivoRechazo
                WHERE id = :id
                """);
    }

    @Test
    void updatesPinAndStateInOneStatement() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        assertThat(repository.updatePinAndState("req-1", "000042", EXPIRES, GENERATED,
                PortabilityStatus.PIN_GENERATED)).isEqualTo(1);
        var params = captureUpdate("""
                UPDATE portability_request
                SET pin = :pin, pin_expiracion = :pinExpiracion,
                    fecha_pin_generado = :fechaPinGenerado, estado = :estado
                WHERE id = :id
                """);
        assertThat(params.getValues()).hasSize(5).containsEntry("pin", "000042")
                .containsEntry("pinExpiracion", EXPIRES).containsEntry("fechaPinGenerado", GENERATED)
                .containsEntry("estado", "PIN_GENERATED");
        assertThat(params.getSqlType("pinExpiracion")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(params.getSqlType("fechaPinGenerado")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    void recordsFailureReasonWithoutChangingOtherColumns() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        assertThat(repository.updateFailureReason("req-1", "El operador donante devolvió HTTP 503."))
                .isEqualTo(1);
        var params = captureUpdate("UPDATE portability_request SET motivo_rechazo = :motivo WHERE id = :id");
        assertThat(params.getValues()).hasSize(2)
                .containsEntry("motivo", "El operador donante devolvió HTTP 503.");
    }

    private MapSqlParameterSource captureUpdate(String expectedSql) {
        var sql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).isEqualToIgnoringWhitespace(expectedSql);
        assertThat(params.getValue().getValue("id")).isEqualTo("req-1");
        return params.getValue();
    }

    private PortabilityRequest request() {
        return new PortabilityRequest("req-1", "595981123456", "0012345", "DONANTE", "RECEPTOR",
                PortabilityStatus.REJECTED, "001234", EXPIRES, 2, CREATED, GENERATED, CONFIRMED,
                COMPLETED, "Motivo de prueba");
    }
}
