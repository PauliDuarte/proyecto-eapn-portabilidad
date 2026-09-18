package py.edu.ucom.is2.eapn.route;

/** Mensaje seguro: no incluye PIN, documento ni cuerpo de respuesta del donante. */
public class DonorNotificationException extends RuntimeException {

    public DonorNotificationException(String message) {
        super(message);
    }
}
