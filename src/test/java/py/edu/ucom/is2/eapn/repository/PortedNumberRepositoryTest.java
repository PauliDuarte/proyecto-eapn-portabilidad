package py.edu.ucom.is2.eapn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import py.edu.ucom.is2.eapn.model.PortedNumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortedNumberRepositoryTest {

    private static final PortedNumber NUMBER = new PortedNumber("595981123456", "ANTERIOR", "ACTUAL",
            OffsetDateTime.parse("2026-09-17T11:00:00-03:00"));

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private ResultSet rs;
    private PortedNumberRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PortedNumberRepository(jdbc);
    }

    @Test
    void insertsEveryFieldWithTimestampWithTimeZone() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        assertThat(repository.insert(NUMBER)).isEqualTo(1);

        var sql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).isEqualToIgnoringWhitespace("""
                INSERT INTO ported_number (msisdn, operador_anterior, operador_actual, fecha_portacion)
                VALUES (:msisdn, :operadorAnterior, :operadorActual, :fechaPortacion)
                """);
        assertThat(params.getValue().getValues()).hasSize(4)
                .containsEntry("msisdn", NUMBER.msisdn())
                .containsEntry("operadorAnterior", NUMBER.operadorAnterior())
                .containsEntry("operadorActual", NUMBER.operadorActual())
                .containsEntry("fechaPortacion", NUMBER.fechaPortacion());
        assertThat(params.getValue().getSqlType("fechaPortacion")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    void findsByMsisdnUsingBoundParameterAndNumberMapper() {
        when(jdbc.query(eq("SELECT * FROM ported_number WHERE msisdn = :msisdn"),
                any(MapSqlParameterSource.class), same(PortedNumberRepository.ROW_MAPPER)))
                .thenReturn(List.of(NUMBER));

        assertThat(repository.findByMsisdn(NUMBER.msisdn())).contains(NUMBER);

        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(anyString(), params.capture(), same(PortedNumberRepository.ROW_MAPPER));
        assertThat(params.getValue().getValues()).containsOnlyKeys("msisdn")
                .containsEntry("msisdn", NUMBER.msisdn());
    }

    @Test
    void returnsEmptyWhenMsisdnDoesNotExist() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                same(PortedNumberRepository.ROW_MAPPER))).thenReturn(List.of());
        assertThat(repository.findByMsisdn("missing")).isEmpty();
    }

    @Test
    void propagatesDuplicateKeyWithoutOverwritingNumber() {
        var failure = new DuplicateKeyException("Duplicated msisdn");
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);
        assertThatThrownBy(() -> repository.insert(NUMBER)).isSameAs(failure);
    }

    @Test
    void doesNotTreatDatabaseFailureAsMissingNumber() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                same(PortedNumberRepository.ROW_MAPPER))).thenThrow(failure);
        assertThatThrownBy(() -> repository.findByMsisdn(NUMBER.msisdn())).isSameAs(failure);
    }

    @Test
    void mapsEveryColumnAndPreservesTimestampInstant() throws SQLException {
        when(rs.getString("msisdn")).thenReturn(NUMBER.msisdn());
        when(rs.getString("operador_anterior")).thenReturn(NUMBER.operadorAnterior());
        when(rs.getString("operador_actual")).thenReturn(NUMBER.operadorActual());
        when(rs.getObject("fecha_portacion", OffsetDateTime.class)).thenReturn(NUMBER.fechaPortacion());

        var mapped = PortedNumberRepository.ROW_MAPPER.mapRow(rs, 0);
        assertThat(mapped).isEqualTo(NUMBER);
        assertThat(mapped.fechaPortacion().toInstant()).isEqualTo(NUMBER.fechaPortacion().toInstant());
    }
}
