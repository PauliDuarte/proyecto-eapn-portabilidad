package py.edu.ucom.is2.eapn.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinResponse;
import py.edu.ucom.is2.eapn.model.dto.DonorApprovalResponse;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

@Service
public class DonorApprovalService {

    private final PortabilityRequestRepository repository;

    public DonorApprovalService(PortabilityRequestRepository repository) {
        this.repository = repository;
    }

    public PortabilityRequest begin(PortabilityRequest confirmed) {
        if (confirmed.estado() != PortabilityStatus.CONFIRMED) {
            throw new IllegalStateException("La consulta al donante requiere un PIN confirmado.");
        }
        requireUpdated(repository.markPendingDonor(confirmed.id()));
        return new PortabilityRequest(confirmed.id(), confirmed.msisdn(), confirmed.documentoTitular(),
                confirmed.operadorDonante(), confirmed.operadorReceptor(), PortabilityStatus.PENDING_DONOR,
                confirmed.pin(), confirmed.pinExpiracion(), confirmed.intentosConfirmacion(),
                confirmed.fechaCreacion(), confirmed.fechaPinGenerado(), confirmed.fechaPinConfirmado(),
                confirmed.fechaCompletada(), confirmed.motivoRechazo());
    }

    public ConfirmPinResponse applyDecision(DonorApprovalResponse decision) {
        return switch (decision.estado()) {
            case "APPROVED" -> {
                requireUpdated(repository.approveByDonor(decision.requestId()));
                yield new ConfirmPinResponse(decision.requestId(), PortabilityStatus.APPROVED,
                        "Solicitud aprobada por el operador donante");
            }
            case "REJECTED" -> {
                requireUpdated(repository.rejectByDonor(decision.requestId(), decision.motivo()));
                yield new ConfirmPinResponse(decision.requestId(), PortabilityStatus.REJECTED, decision.motivo());
            }
            default -> throw new DonorApprovalException("El donante devolvió un estado desconocido.");
        };
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new DataIntegrityViolationException("No se pudo persistir la transición de aprobación del donante.");
        }
    }
}
