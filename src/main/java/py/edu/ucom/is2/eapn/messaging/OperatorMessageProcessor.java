package py.edu.ucom.is2.eapn.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import py.edu.ucom.is2.eapn.repository.ProcessedMessageRepository;

@Component
public class OperatorMessageProcessor {
    public static final String COMMAND = "operatorCommand";
    public static final String CLAIMED = "messageClaimed";
    private final ObjectMapper mapper;
    private final ProcessedMessageRepository repository;
    private final OperatorExecutor executor;

    public OperatorMessageProcessor(ObjectMapper mapper, ProcessedMessageRepository repository, OperatorExecutor executor) {
        this.mapper = mapper;
        this.repository = repository;
        this.executor = executor;
    }

    public void process(Exchange exchange, Operation expected) throws Exception {
        OperatorCommand command = exchange.getProperty(COMMAND, OperatorCommand.class);
        if (command == null) {
            command = mapper.readValue(exchange.getMessage().getBody(String.class), OperatorCommand.class);
            validate(command, expected, exchange.getMessage().getHeader("JMSCorrelationID", String.class));
            exchange.setProperty(COMMAND, command);
        }
        if (!Boolean.TRUE.equals(exchange.getProperty(CLAIMED))) {
            if (!repository.claim(expected.name(), command.requestId())) {
                String cached = repository.findReply(expected.name(), command.requestId()).orElseThrow(
                        () -> new MessageProcessingException("Mensaje duplicado todavía en proceso; requiere revisión."));
                exchange.getMessage().setBody(cached);
                return;
            }
            exchange.setProperty(CLAIMED, true);
        }
        // Si solo falla guardar la respuesta JDBC, reintentar ese paso sin repetir el HTTP exitoso.
        String json = exchange.getProperty("executedReply", String.class);
        if (json == null) {
            json = mapper.writeValueAsString(executor.execute(command));
            exchange.setProperty("executedReply", json);
        }
        repository.complete(expected.name(), command.requestId(), json);
        exchange.setProperty(CLAIMED, false);
        exchange.getMessage().setBody(json);
    }

    private void validate(OperatorCommand command, Operation expected, String correlation) {
        if (command == null || command.operation() != expected || command.requestId() == null
                || !command.requestId().matches("[A-Za-z0-9_-]{1,64}")
                || !command.requestId().equals(correlation) || command.msisdn() == null
                || command.payload() == null || !command.payload().isObject()
                || !command.requestId().equals(command.payload().path("request_id").asText())
                || !command.msisdn().equals(command.payload().path("msisdn").asText())) {
            throw new MessageProcessingException("Mensaje sin operación o correlación válida.");
        }
    }

    public void releaseFailedClaim(Exchange exchange) {
        var command = exchange.getProperty(COMMAND, OperatorCommand.class);
        if (command != null && Boolean.TRUE.equals(exchange.getProperty(CLAIMED))) {
            repository.release(command.operation().name(), command.requestId());
        }
    }
}
