package py.edu.ucom.is2.eapn.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReceiverNotificationResponse(
        @JsonProperty("request_id") String requestId,
        String estado) {
}
