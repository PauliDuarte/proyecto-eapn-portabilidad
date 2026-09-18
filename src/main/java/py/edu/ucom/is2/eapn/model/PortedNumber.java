package py.edu.ucom.is2.eapn.model;

import java.time.OffsetDateTime;

public record PortedNumber(
        String msisdn,
        String operadorAnterior,
        String operadorActual,
        OffsetDateTime fechaPortacion) {
}
