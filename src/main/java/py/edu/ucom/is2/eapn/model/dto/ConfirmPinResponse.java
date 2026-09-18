package py.edu.ucom.is2.eapn.model.dto;

import py.edu.ucom.is2.eapn.model.PortabilityStatus;

public record ConfirmPinResponse(String id, PortabilityStatus estado, String mensaje) {
}
