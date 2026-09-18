package py.edu.ucom.is2.eapn.model.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

/** Vista pública de consulta: no incluye PIN, documento ni detalles de integración. */
public record PortabilityStatusResponse(
        String id,
        String msisdn,
        @JsonProperty("operador_donante") String operadorDonante,
        @JsonProperty("operador_receptor") String operadorReceptor,
        PortabilityStatus estado,
        @JsonProperty("fecha_creacion") OffsetDateTime fechaCreacion,
        @JsonProperty("fecha_pin_generado") OffsetDateTime fechaPinGenerado,
        @JsonProperty("fecha_pin_confirmado") OffsetDateTime fechaPinConfirmado,
        @JsonProperty("fecha_completada") OffsetDateTime fechaCompletada,
        @JsonProperty("motivo_rechazo") @JsonInclude(JsonInclude.Include.NON_NULL) String motivoRechazo) {

    public static PortabilityStatusResponse from(PortabilityRequest request) {
        // El flujo de entrega de PIN también usa motivo_rechazo para fallos técnicos.
        // Solo un REJECTED de negocio debe publicar su motivo.
        return new PortabilityStatusResponse(request.id(), request.msisdn(), request.operadorDonante(),
                request.operadorReceptor(), request.estado(), request.fechaCreacion(), request.fechaPinGenerado(),
                request.fechaPinConfirmado(), request.fechaCompletada(),
                request.estado() == PortabilityStatus.REJECTED ? request.motivoRechazo() : null);
    }
}
