package py.edu.ucom.is2.eapn.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import py.edu.ucom.is2.eapn.model.dto.PortabilityStatusResponse;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

@Service
public class PortabilityQueryService {
    private final PortabilityRequestRepository repository;

    public PortabilityQueryService(PortabilityRequestRepository repository) {
        this.repository = repository;
    }

    public Optional<PortabilityStatusResponse> findById(String id) {
        return repository.findById(id).map(PortabilityStatusResponse::from);
    }
}
