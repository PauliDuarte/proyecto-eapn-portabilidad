package py.edu.ucom.is2.eapn.service;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import py.edu.ucom.is2.eapn.model.PortedNumber;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinResponse;
import py.edu.ucom.is2.eapn.model.dto.PortabilityResultNotification;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;
import py.edu.ucom.is2.eapn.repository.PortedNumberRepository;

@Service
public class PortabilityFinalizationService {

    private final PortabilityRequestRepository requests;
    private final PortedNumberRepository portedNumbers;
    private final Clock clock;

    public PortabilityFinalizationService(PortabilityRequestRepository requests,
            PortedNumberRepository portedNumbers, Clock clock) {
        this.requests = requests;
        this.portedNumbers = portedNumbers;
        this.clock = clock;
    }

    /** La decisión ya está persistida. Retorna al orquestador después del commit, sin llamadas HTTP. */
    @Transactional
    public PortabilityResultNotification finish(PortabilityRequest request, ConfirmPinResponse decision) {
        if (!request.id().equals(decision.id())) {
            throw new IllegalArgumentException("La decisión no corresponde a la solicitud.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (decision.estado() == PortabilityStatus.APPROVED) {
            var number = new PortedNumber(request.msisdn(), request.operadorDonante(), request.operadorReceptor(), now);
            requireUpdated(portedNumbers.insert(number));
            requireUpdated(requests.markCompleted(request.id(), now));
            return notification(request, PortabilityStatus.COMPLETED, now, null);
        }
        if (decision.estado() == PortabilityStatus.REJECTED) {
            // En rechazo es la fecha del resultado, no fecha_completada de una portación.
            return notification(request, PortabilityStatus.REJECTED, now, decision.mensaje());
        }
        throw new IllegalStateException("La finalización requiere una decisión APPROVED o REJECTED.");
    }

    private PortabilityResultNotification notification(PortabilityRequest request, PortabilityStatus state,
            OffsetDateTime timestamp, String reason) {
        return new PortabilityResultNotification(request.id(), request.msisdn(), state,
                request.operadorDonante(), request.operadorReceptor(), timestamp, reason);
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new DataIntegrityViolationException("No se pudo materializar la portabilidad completa.");
        }
    }
}
