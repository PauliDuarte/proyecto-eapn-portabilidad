package py.edu.ucom.is2.eapn.messaging;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.apache.camel.ProducerTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;

@Component
public class StateEventPublisher {
    private final ProducerTemplate producer;
    private final Clock clock;
    private final boolean enabled;

    public StateEventPublisher(ProducerTemplate producer, Clock clock,
            @Value("${app.messaging.enabled:true}") boolean enabled) {
        this.producer = producer;
        this.clock = clock;
        this.enabled = enabled;
    }

    public void publish(PortabilityRequest request) {
        publish(request.id(), request.msisdn(), request.estado());
    }

    public void publish(String id, String msisdn, PortabilityStatus state) {
        publish(new StateEvent(id, msisdn, state, OffsetDateTime.now(clock), "STATE_CHANGED"));
    }

    public void publish(StateEvent event) {
        if (enabled && event != null) {
            // Exchange nuevo con un DTO seguro: el Wire Tap no copia el modelo que contiene PIN.
            producer.sendBodyAndHeader("direct:publish-state-event", event, "JMSCorrelationID", event.requestId());
        }
    }
}
