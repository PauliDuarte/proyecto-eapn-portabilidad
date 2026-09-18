package py.edu.ucom.is2.eapn.model;

import java.time.OffsetDateTime;

/** Datos de una solicitud; las fechas y el estado son provistos por el llamador. */
public record PortabilityRequest(
        String id,
        String msisdn,
        String documentoTitular,
        String operadorDonante,
        String operadorReceptor,
        PortabilityStatus estado,
        String pin,
        OffsetDateTime pinExpiracion,
        int intentosConfirmacion,
        OffsetDateTime fechaCreacion,
        OffsetDateTime fechaPinGenerado,
        OffsetDateTime fechaPinConfirmado,
        OffsetDateTime fechaCompletada,
        String motivoRechazo) {
}
