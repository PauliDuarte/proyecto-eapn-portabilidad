package py.edu.ucom.is2.eapn.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import py.edu.ucom.is2.eapn.model.PortedNumber;

@Repository
public class PortedNumberRepository {

    static final RowMapper<PortedNumber> ROW_MAPPER = (rs, rowNum) -> new PortedNumber(
            rs.getString("msisdn"),
            rs.getString("operador_anterior"),
            rs.getString("operador_actual"),
            rs.getObject("fecha_portacion", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public PortedNumberRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(PortedNumber number) {
        return jdbc.update("""
                INSERT INTO ported_number (msisdn, operador_anterior, operador_actual, fecha_portacion)
                VALUES (:msisdn, :operadorAnterior, :operadorActual, :fechaPortacion)
                """, new MapSqlParameterSource()
                .addValue("msisdn", number.msisdn())
                .addValue("operadorAnterior", number.operadorAnterior())
                .addValue("operadorActual", number.operadorActual())
                .addValue("fechaPortacion", number.fechaPortacion(), Types.TIMESTAMP_WITH_TIMEZONE));
    }

    public Optional<PortedNumber> findByMsisdn(String msisdn) {
        return jdbc.query("SELECT * FROM ported_number WHERE msisdn = :msisdn",
                new MapSqlParameterSource("msisdn", msisdn), ROW_MAPPER).stream().findFirst();
    }
}
