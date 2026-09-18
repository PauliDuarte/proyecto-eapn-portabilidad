package py.edu.ucom.is2.eapn.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DonorApprovalResponse(
        @JsonProperty("request_id") String requestId,
        String estado,
        String motivo) {
}
