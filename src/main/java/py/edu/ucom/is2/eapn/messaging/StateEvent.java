package py.edu.ucom.is2.eapn.messaging;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

public record StateEvent(@JsonProperty("request_id") String requestId,
        String msisdn, PortabilityStatus estado, OffsetDateTime timestamp, String operation) {
}
