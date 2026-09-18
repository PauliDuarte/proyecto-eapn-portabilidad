package py.edu.ucom.is2.eapn.service;

/** Error técnico de entrega; el resultado de negocio ya está persistido. */
public class ReceiverNotificationException extends RuntimeException {

    public ReceiverNotificationException(String message) {
        super(message);
    }
}
