package py.edu.ucom.is2.eapn.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import py.edu.ucom.is2.eapn.messaging.StateEvent;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinRequest;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static py.edu.ucom.is2.eapn.service.PinConfirmationException.Reason.*;

@Service
public class PinConfirmationService {

    private static final Pattern PIN_FORMAT = Pattern.compile("[0-9]{6}");
    private final PortabilityRequestRepository repository;
    private final Clock clock;

    public PinConfirmationService(PortabilityRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PortabilityRequest confirm(String id, ConfirmPinRequest input) {
        var request = repository.findById(id).orElseThrow(() -> new PinConfirmationException(
                NOT_FOUND, "La solicitud de portabilidad no existe."));
        if (request.estado() != PortabilityStatus.PIN_GENERATED) {
            throw new PinConfirmationException(INVALID_STATE,
                    "Solo se puede confirmar una solicitud en estado PIN_GENERATED.");
        }
        if (request.pin() == null || !PIN_FORMAT.matcher(request.pin()).matches()
                || request.pinExpiracion() == null || request.fechaPinGenerado() == null) {
            throw new PinConfirmationException(MISSING_PIN,
                    "La solicitud no tiene un PIN generado con sus fechas de vigencia.");
        }
        if (input == null || input.pin() == null || !PIN_FORMAT.matcher(input.pin()).matches()) {
            throw new PinConfirmationException(MALFORMED_PIN, "pin debe contener exactamente 6 dígitos.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        // La expiración tiene prioridad si el PIN está además equivocado.
        if (!now.isBefore(request.pinExpiracion())) {
            reject(request, now, EXPIRED_PIN, "El PIN ha expirado.");
        }
        if (!request.pin().equals(input.pin())) {
            reject(request, now, INCORRECT_PIN, "El PIN es incorrecto.");
        }

        requireUpdated(repository.confirmPin(id, now));
        return new PortabilityRequest(request.id(), request.msisdn(), request.documentoTitular(),
                request.operadorDonante(), request.operadorReceptor(), PortabilityStatus.CONFIRMED,
                request.pin(), request.pinExpiracion(), request.intentosConfirmacion() + 1,
                request.fechaCreacion(), request.fechaPinGenerado(), now, request.fechaCompletada(), null);
    }

    private void reject(PortabilityRequest request, OffsetDateTime now, PinConfirmationException.Reason reason, String message) {
        // El UPDATE incluye el intento. No envolver en una transacción que revierta al lanzar este error.
        requireUpdated(repository.rejectPinConfirmation(request.id(), message));
        throw new PinConfirmationException(reason, message,
                new StateEvent(request.id(), request.msisdn(), PortabilityStatus.REJECTED, now, "STATE_CHANGED"));
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new PinConfirmationException(STATE_CHANGED,
                    "La solicitud cambió de estado y ya no se puede confirmar su PIN.");
        }
    }
}
