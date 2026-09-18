package py.edu.ucom.is2.eapn.messaging;

public enum Operation {
    PIN_NOTIFICATION("eapn.pin.notification", "direct:enviar-pin-operador-http"),
    DONOR_APPROVAL("eapn.donor.approval", "direct:solicitar-aprobacion-donante-http"),
    RECEIVER_NOTIFICATION("eapn.receiver.notification", "direct:notificar-resultado-receptor-http");

    public final String queue;
    public final String endpoint;

    Operation(String queue, String endpoint) {
        this.queue = queue;
        this.endpoint = endpoint;
    }
}
