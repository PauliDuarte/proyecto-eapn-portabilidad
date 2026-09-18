package py.edu.ucom.is2.eapn.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Contrato interno con el donante; nunca se usa como respuesta pública. */
public record PinNotificationRequest(
        @JsonProperty("request_id") String requestId,
        String msisdn,
        @JsonProperty("documento_titular") String documentoTitular,
        String pin) {
}
