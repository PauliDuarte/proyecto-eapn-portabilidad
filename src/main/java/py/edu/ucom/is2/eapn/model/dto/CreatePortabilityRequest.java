package py.edu.ucom.is2.eapn.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreatePortabilityRequest(
        String msisdn,
        @JsonProperty("documento_titular") String documentoTitular,
        @JsonProperty("operador_donante") String operadorDonante,
        @JsonProperty("operador_receptor") String operadorReceptor) {
}
