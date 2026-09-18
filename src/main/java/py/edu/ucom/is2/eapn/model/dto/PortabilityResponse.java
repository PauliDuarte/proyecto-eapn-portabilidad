package py.edu.ucom.is2.eapn.model.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

public record PortabilityResponse(
        String id,
        String msisdn,
        @JsonProperty("operador_donante") String operadorDonante,
        @JsonProperty("operador_receptor") String operadorReceptor,
        PortabilityStatus estado,
        @JsonProperty("fecha_creacion") OffsetDateTime fechaCreacion) {

    public static PortabilityResponse from(PortabilityRequest request) {
        return new PortabilityResponse(request.id(), request.msisdn(), request.operadorDonante(),
                request.operadorReceptor(), request.estado(), request.fechaCreacion());
    }
}
