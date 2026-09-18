package py.edu.ucom.is2.eapn.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

@Service
public class PinGenerationService {

    private final PortabilityRequestRepository repository;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public PinGenerationService(PortabilityRequestRepository repository, Clock clock) {
        this(repository, clock, new SecureRandom());
    }

    PinGenerationService(PortabilityRequestRepository repository, Clock clock, SecureRandom random) {
        this.repository = repository;
        this.clock = clock;
        this.random = random;
    }

    public PortabilityRequest generate(PortabilityRequest request) {
        String pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        OffsetDateTime generated = OffsetDateTime.now(clock);
        OffsetDateTime expiration = generated.plusMinutes(15);
        if (repository.updatePinAndState(request.id(), pin, expiration, generated,
                PortabilityStatus.PIN_GENERATED) != 1) {
            throw new DataIntegrityViolationException("No se pudo persistir el PIN generado.");
        }
        return new PortabilityRequest(request.id(), request.msisdn(), request.documentoTitular(),
                request.operadorDonante(), request.operadorReceptor(), PortabilityStatus.PIN_GENERATED,
                pin, expiration, request.intentosConfirmacion(), request.fechaCreacion(), generated,
                request.fechaPinConfirmado(), request.fechaCompletada(), request.motivoRechazo());
    }

    public void recordNotificationFailure(String requestId, String reason) {
        if (repository.updateFailureReason(requestId, reason) != 1) {
            throw new DataIntegrityViolationException("No se pudo registrar el error de notificación.");
        }
    }
}
