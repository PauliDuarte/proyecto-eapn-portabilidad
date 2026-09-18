package py.edu.ucom.is2.eapn.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;
import py.edu.ucom.is2.eapn.model.dto.*;

@Component
public class OperatorExecutor {
    private final ProducerTemplate producer;
    private final ObjectMapper mapper;

    public OperatorExecutor(ProducerTemplate producer, ObjectMapper mapper) {
        this.producer = producer;
        this.mapper = mapper;
    }

    public OperatorReply execute(OperatorCommand command) throws Exception {
        Object request = switch (command.operation()) {
            case PIN_NOTIFICATION -> mapper.treeToValue(command.payload(), PinNotificationRequest.class);
            case DONOR_APPROVAL -> mapper.treeToValue(command.payload(), DonorApprovalRequest.class);
            case RECEIVER_NOTIFICATION -> mapper.treeToValue(command.payload(), PortabilityResultNotification.class);
        };
        // Un Exchange nuevo aísla los headers HTTP de JMSReplyTo/JMSCorrelationID.
        Object result = producer.requestBodyAndHeader(command.operation().endpoint, request, "operator", command.operator());
        return new OperatorReply(command.requestId(), true, mapper.valueToTree(result), null);
    }
}
