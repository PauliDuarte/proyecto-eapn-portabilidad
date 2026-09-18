package py.edu.ucom.is2.eapn.messaging;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

/** No copiar cuerpos, headers HTTP ni mensajes arbitrarios de excepciones. */
public record TechnicalError(@JsonProperty("request_id") String requestId,
        String origin, String operation, String summary,
        String exceptionType, OffsetDateTime timestamp) {
}
