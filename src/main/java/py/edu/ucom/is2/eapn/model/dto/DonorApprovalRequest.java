package py.edu.ucom.is2.eapn.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DonorApprovalRequest(
        @JsonProperty("request_id") String requestId,
        String msisdn,
        @JsonProperty("documento_titular") String documentoTitular,
        @JsonProperty("operador_receptor") String operadorReceptor) {
}
