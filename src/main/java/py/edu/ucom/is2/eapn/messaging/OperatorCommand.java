package py.edu.ucom.is2.eapn.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Solo el comando PIN_NOTIFICATION contiene PIN: es necesario para entregarlo. */
public record OperatorCommand(@JsonProperty("request_id") String requestId,
        String msisdn, Operation operation, String operator, JsonNode payload) {
}
