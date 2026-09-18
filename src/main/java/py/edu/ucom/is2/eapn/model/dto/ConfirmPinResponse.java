package py.edu.ucom.is2.eapn.model.dto;

import py.edu.ucom.is2.eapn.model.PortabilityStatus;

public record ConfirmPinResponse(String id, PortabilityStatus estado, String mensaje) {

    public static ConfirmPinResponse fromResult(PortabilityResultNotification result) {
        String message = result.estado() == PortabilityStatus.COMPLETED
                ? "Portabilidad completada correctamente" : result.motivo();
        return new ConfirmPinResponse(result.requestId(), result.estado(), message);
    }
}
