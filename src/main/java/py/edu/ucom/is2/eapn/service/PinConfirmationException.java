package py.edu.ucom.is2.eapn.service;

import py.edu.ucom.is2.eapn.messaging.StateEvent;

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
    private final StateEvent stateEvent;

    public PinConfirmationException(Reason reason, String message) {
        this(reason, message, null);
    }

    public PinConfirmationException(Reason reason, String message, StateEvent stateEvent) {
        super(message);
        this.stateEvent = stateEvent;
        this.reason = reason;
    }

    public StateEvent stateEvent() {
        return stateEvent;
    }

    public Reason reason() {
        return reason;
    }
}
