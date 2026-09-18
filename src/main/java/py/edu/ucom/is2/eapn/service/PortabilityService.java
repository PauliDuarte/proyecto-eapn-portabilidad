package py.edu.ucom.is2.eapn.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.dto.CreatePortabilityRequest;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

@Service
public class PortabilityService {

    // Validación de formato móvil; no verifica la asignación ni existencia del número.
    private static final Pattern MSISDN = Pattern.compile("\\+5959[0-9]{8}");
    private static final List<String> OPERATORS = List.of("Tigo", "Personal", "Claro", "Vox");

    private final PortabilityRequestRepository repository;
    private final Clock clock;

    public PortabilityService(PortabilityRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PortabilityRequest create(CreatePortabilityRequest input) {
        if (input == null) {
            throw new RequestValidationException("El cuerpo de la solicitud es obligatorio.");
        }
        if (input.msisdn() == null || !MSISDN.matcher(input.msisdn()).matches()) {
            throw new RequestValidationException("msisdn debe tener formato móvil paraguayo: +5959 seguido de 8 dígitos.");
        }
        if (input.documentoTitular() == null || input.documentoTitular().isBlank()) {
            throw new RequestValidationException("documento_titular es obligatorio y no puede estar vacío.");
        }
        String documento = input.documentoTitular().strip();
        if (documento.length() > 50) {
            throw new RequestValidationException("documento_titular no puede superar 50 caracteres.");
        }
        String donante = normalizeOperator(input.operadorDonante(), "operador_donante");
        String receptor = normalizeOperator(input.operadorReceptor(), "operador_receptor");
        if (donante.equals(receptor)) {
            throw new RequestValidationException("operador_donante y operador_receptor deben ser diferentes.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        String id = "REQ-" + now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().replace("-", "");
        var request = new PortabilityRequest(id, input.msisdn(), documento, donante, receptor,
                PortabilityStatus.CREATED, null, null, 0, now, null, null, null, null);
        if (repository.insert(request) != 1) {
            throw new DataIntegrityViolationException("No se insertó la solicitud de portabilidad.");
        }
        return request;
    }

    private String normalizeOperator(String value, String field) {
        if (value != null) {
            for (String operator : OPERATORS) {
                if (operator.equalsIgnoreCase(value.strip())) {
                    return operator;
                }
            }
        }
        throw new RequestValidationException(field + " debe ser uno de: Tigo, Personal, Claro, Vox.");
    }
}
