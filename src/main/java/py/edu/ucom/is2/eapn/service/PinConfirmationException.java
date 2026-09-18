package py.edu.ucom.is2.eapn.service;

public class PinConfirmationException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        INVALID_STATE,
        MISSING_PIN,
        MALFORMED_PIN,
        INCORRECT_PIN,
        EXPIRED_PIN,
        STATE_CHANGED
    }

    private final Reason reason;

    public PinConfirmationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
