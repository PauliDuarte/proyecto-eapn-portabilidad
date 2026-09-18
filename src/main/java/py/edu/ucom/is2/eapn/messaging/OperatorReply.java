package py.edu.ucom.is2.eapn.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record OperatorReply(@JsonProperty("request_id") String requestId,
        boolean successful, JsonNode result, String error) {
}
