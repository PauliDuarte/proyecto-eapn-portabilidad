package py.edu.ucom.is2.eapn.route;

import java.time.Clock;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import py.edu.ucom.is2.eapn.messaging.*;

@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConsumerRoute extends RouteBuilder {
    private final OperatorMessageProcessor processor;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProducerTemplate producer;

    public MessagingConsumerRoute(OperatorMessageProcessor processor, ObjectMapper mapper, Clock clock, ProducerTemplate producer) {
        this.processor = processor;
        this.mapper = mapper;
        this.clock = clock;
        this.producer = producer;
    }

    @Override
    public void configure() {
        // Dos redeliveries + intento original = tres intentos. El Processor completo se reejecuta.
        errorHandler(deadLetterChannel("direct:messaging-error").deadLetterHandleNewException(false)
                .maximumRedeliveries(2).redeliveryDelay(200)
                .useOriginalMessage()
                .logExhausted(false).logRetryAttempted(false).logStackTrace(false));

        for (Operation operation : Operation.values()) {
            from("jms:queue:" + operation.queue + "?acknowledgementModeName=CLIENT_ACKNOWLEDGE")
                .routeId("consume-" + operation.queue)
                .setProperty("messageOrigin", constant(operation.queue))
                .process(exchange -> processor.process(exchange, operation));
        }

        from("direct:publish-state-event").routeId("publish-state-event")
            .wireTap("direct:deliver-state-event").end();

        from("direct:deliver-state-event").routeId("deliver-state-event")
            .setProperty("messageOrigin", constant("eapn.state.events"))
            .setHeader("operation", constant("STATE_CHANGED"))
            .process(exchange -> exchange.getMessage().setBody(
                    mapper.writeValueAsString(exchange.getMessage().getBody(StateEvent.class))))
            .to(ExchangePattern.InOnly, "jms:topic:eapn.state.events?deliveryPersistent=true");

        from("direct:messaging-error").routeId("messaging-error")
            .errorHandler(noErrorHandler())
            .process(exchange -> {
                String id = exchange.getMessage().getHeader("JMSCorrelationID", String.class);
                // Incluso mensajes corruptos van al DLC, sin copiar su contenido.
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) id = "UNKNOWN";
                String origin = exchange.getProperty("messageOrigin", String.class);
                var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                Throwable root = failure;
                while (root != null && root.getCause() != null) root = root.getCause();
                var command = exchange.getProperty(OperatorMessageProcessor.COMMAND, OperatorCommand.class);
                String operation = command == null ? "UNKNOWN" : command.operation().name();
                var error = new TechnicalError(id, origin, operation,
                        "Procesamiento técnico agotado tras 3 intentos; revisar el consumidor.",
                        root == null ? "UnknownException" : root.getClass().getSimpleName(), OffsetDateTime.now(clock));
                final String correlation = id;
                // Exchange separado: ni JMSReplyTo ni PIN se copian a la cola de error.
                var sent = producer.send("jms:queue:eapn.error?deliveryPersistent=true", ExchangePattern.InOnly, message -> {
                    message.getMessage().setBody(mapper.writeValueAsString(error));
                    message.getMessage().setHeader("JMSCorrelationID", correlation);
                    message.getMessage().setHeader("operation", "TECHNICAL_ERROR");
                });
                if (sent.isFailed()) throw new MessageProcessingException("No se pudo publicar en eapn.error.");
                processor.releaseFailedClaim(exchange);
                exchange.getMessage().setBody(mapper.writeValueAsString(
                        new OperatorReply(id, false, null, "Error técnico; revisar eapn.error.")));
            });
    }
}
