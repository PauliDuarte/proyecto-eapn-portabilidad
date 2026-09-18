package py.edu.ucom.is2.eapn.model.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

/** Contrato final con el receptor: no contiene PIN ni documento del titular. */
public record PortabilityResultNotification(
        @JsonProperty("request_id") String requestId,
        String msisdn,
        PortabilityStatus estado,
        @JsonProperty("operador_donante") String operadorDonante,
        @JsonProperty("operador_receptor") String operadorReceptor,
        @JsonProperty("fecha_finalizacion") OffsetDateTime fechaFinalizacion,
        String motivo) {
}
