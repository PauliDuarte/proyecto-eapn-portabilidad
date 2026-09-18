package py.edu.ucom.is2.eapn.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

/** Persistencia sin transiciones de negocio. Las escrituras devuelven filas afectadas. */
@Repository
public class PortabilityRequestRepository {

    static final RowMapper<PortabilityRequest> ROW_MAPPER = (rs, rowNum) -> new PortabilityRequest(
            rs.getString("id"),
            rs.getString("msisdn"),
            rs.getString("documento_titular"),
            rs.getString("operador_donante"),
            rs.getString("operador_receptor"),
            PortabilityStatus.valueOf(rs.getString("estado")),
            rs.getString("pin"),
            rs.getObject("pin_expiracion", OffsetDateTime.class),
            rs.getInt("intentos_confirmacion"),
            rs.getObject("fecha_creacion", OffsetDateTime.class),
            rs.getObject("fecha_pin_generado", OffsetDateTime.class),
            rs.getObject("fecha_pin_confirmado", OffsetDateTime.class),
            rs.getObject("fecha_completada", OffsetDateTime.class),
            rs.getString("motivo_rechazo"));

    private final NamedParameterJdbcTemplate jdbc;

    public PortabilityRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserta todos los campos, incluida fechaCreacion (obligatoria en el esquema). */
    public int insert(PortabilityRequest request) {
        return jdbc.update("""
                INSERT INTO portability_request (
                    id, msisdn, documento_titular, operador_donante, operador_receptor,
                    estado, pin, pin_expiracion, intentos_confirmacion, fecha_creacion,
                    fecha_pin_generado, fecha_pin_confirmado, fecha_completada, motivo_rechazo
                ) VALUES (
                    :id, :msisdn, :documentoTitular, :operadorDonante, :operadorReceptor,
                    :estado, :pin, :pinExpiracion, :intentosConfirmacion, :fechaCreacion,
                    :fechaPinGenerado, :fechaPinConfirmado, :fechaCompletada, :motivoRechazo
                )
                """, new MapSqlParameterSource()
                .addValue("id", request.id())
                .addValue("msisdn", request.msisdn())
                .addValue("documentoTitular", request.documentoTitular())
                .addValue("operadorDonante", request.operadorDonante())
                .addValue("operadorReceptor", request.operadorReceptor())
                .addValue("estado", request.estado().name())
                .addValue("pin", request.pin(), Types.VARCHAR)
                .addValue("pinExpiracion", request.pinExpiracion(), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("intentosConfirmacion", request.intentosConfirmacion())
                .addValue("fechaCreacion", request.fechaCreacion(), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("fechaPinGenerado", request.fechaPinGenerado(), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("fechaPinConfirmado", request.fechaPinConfirmado(), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("fechaCompletada", request.fechaCompletada(), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("motivoRechazo", request.motivoRechazo(), Types.VARCHAR));
    }

    public Optional<PortabilityRequest> findById(String id) {
        return jdbc.query("SELECT * FROM portability_request WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW_MAPPER).stream().findFirst();
    }

    public int updateState(String id, PortabilityStatus estado) {
        return jdbc.update("UPDATE portability_request SET estado = :estado WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("estado", estado.name()));
    }

    public int updatePin(String id, String pin, OffsetDateTime expiracion, OffsetDateTime generado) {
        return jdbc.update("""
                UPDATE portability_request
                SET pin = :pin, pin_expiracion = :pinExpiracion, fecha_pin_generado = :fechaPinGenerado
                WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("pin", pin, Types.VARCHAR)
                .addValue("pinExpiracion", expiracion, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("fechaPinGenerado", generado, Types.TIMESTAMP_WITH_TIMEZONE));
    }

    public int updateConfirmationDate(String id, OffsetDateTime confirmado) {
        return jdbc.update("""
                UPDATE portability_request SET fecha_pin_confirmado = :fechaPinConfirmado WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("fechaPinConfirmado", confirmado, Types.TIMESTAMP_WITH_TIMEZONE));
    }

    /** Una sola escritura evita guardar el PIN y su estado parcialmente. */
    public int updatePinAndState(String id, String pin, OffsetDateTime expiracion,
            OffsetDateTime generado, PortabilityStatus estado) {
        return jdbc.update("""
                UPDATE portability_request
                SET pin = :pin, pin_expiracion = :pinExpiracion,
                    fecha_pin_generado = :fechaPinGenerado, estado = :estado
                WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("pin", pin, Types.VARCHAR)
                .addValue("pinExpiracion", expiracion, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("fechaPinGenerado", generado, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("estado", estado.name()));
    }

    /** Registra el motivo sin cambiar el estado ni las fechas de la solicitud. */
    public int updateFailureReason(String id, String motivo) {
        return jdbc.update("UPDATE portability_request SET motivo_rechazo = :motivo WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("motivo", motivo, Types.VARCHAR));
    }

    /** Confirma e incrementa el intento en una sola escritura, solo desde PIN_GENERATED. */
    public int confirmPin(String id, OffsetDateTime confirmado) {
        return jdbc.update("""
                UPDATE portability_request
                SET estado = 'CONFIRMED', fecha_pin_confirmado = :fechaPinConfirmado,
                    intentos_confirmacion = intentos_confirmacion + 1, motivo_rechazo = NULL
                WHERE id = :id AND estado = 'PIN_GENERATED'
                """, new MapSqlParameterSource("id", id)
                .addValue("fechaPinConfirmado", confirmado, Types.TIMESTAMP_WITH_TIMEZONE));
    }

    /** Rechaza por PIN e incrementa el intento sin marcar la portación como completada. */
    public int rejectPinConfirmation(String id, String motivo) {
        return jdbc.update("""
                UPDATE portability_request
                SET estado = 'REJECTED', motivo_rechazo = :motivo,
                    intentos_confirmacion = intentos_confirmacion + 1
                WHERE id = :id AND estado = 'PIN_GENERATED'
                """, new MapSqlParameterSource("id", id)
                .addValue("motivo", motivo, Types.VARCHAR));
    }

    public int markPendingDonor(String id) {
        return jdbc.update("""
                UPDATE portability_request SET estado = 'PENDING_DONOR'
                WHERE id = :id AND estado = 'CONFIRMED'
                """, new MapSqlParameterSource("id", id));
    }

    public int approveByDonor(String id) {
        return jdbc.update("""
                UPDATE portability_request SET estado = 'APPROVED', motivo_rechazo = NULL
                WHERE id = :id AND estado = 'PENDING_DONOR'
                """, new MapSqlParameterSource("id", id));
    }

    public int rejectByDonor(String id, String motivo) {
        return jdbc.update("""
                UPDATE portability_request SET estado = 'REJECTED', motivo_rechazo = :motivo
                WHERE id = :id AND estado = 'PENDING_DONOR'
                """, new MapSqlParameterSource("id", id).addValue("motivo", motivo, Types.VARCHAR));
    }

    /** Permite una fecha nula si se registra un rechazo sin portación completada. */
    public int updateCompletion(String id, OffsetDateTime completada, String motivoRechazo) {
        return jdbc.update("""
                UPDATE portability_request
                SET fecha_completada = :fechaCompletada, motivo_rechazo = :motivoRechazo
                WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("fechaCompletada", completada, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("motivoRechazo", motivoRechazo, Types.VARCHAR));
    }
}
