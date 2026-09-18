package py.edu.ucom.is2.eapn.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import py.edu.ucom.is2.eapn.messaging.*;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.*;
import py.edu.ucom.is2.eapn.service.DonorApprovalException;
import py.edu.ucom.is2.eapn.service.ReceiverNotificationException;

@Component
public class MessagingGatewayRoute extends RouteBuilder {
    private final ObjectMapper mapper;
    private final ProducerTemplate producer;
    private final OperatorExecutor executor;
    private final boolean enabled;

    public MessagingGatewayRoute(ObjectMapper mapper, ProducerTemplate producer, OperatorExecutor executor,
            @Value("${app.messaging.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.producer = producer;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Override
    public void configure() {
        errorHandler(noErrorHandler());
        from("direct:enviar-pin-operador").routeId("enviar-pin-operador")
            .setProperty(PinNotificationRoute.REQUEST_PROPERTY, body())
            .process(exchange -> {
                var request = exchange.getMessage().getBody(PortabilityRequest.class);
                send(exchange, new OperatorCommand(request.id(), request.msisdn(), Operation.PIN_NOTIFICATION,
                        request.operadorDonante(), mapper.valueToTree(new PinNotificationRequest(request.id(),
                        request.msisdn(), request.documentoTitular(), request.pin()))));
                exchange.getMessage().setBody(request);
            });
        from("direct:solicitar-aprobacion-donante").routeId("solicitar-aprobacion-donante")
            .setProperty(DonorApprovalRoute.REQUEST_PROPERTY, body())
            .process(exchange -> {
                var request = exchange.getMessage().getBody(PortabilityRequest.class);
                var reply = send(exchange, new OperatorCommand(request.id(), request.msisdn(), Operation.DONOR_APPROVAL,
                        request.operadorDonante(), mapper.valueToTree(new DonorApprovalRequest(request.id(),
                        request.msisdn(), request.documentoTitular(), request.operadorReceptor()))));
                exchange.getMessage().setBody(mapper.treeToValue(reply.result(), DonorApprovalResponse.class));
            });
        from("direct:notificar-resultado-receptor").routeId("notificar-resultado-receptor")
            .setProperty(ReceiverNotificationRoute.RESULT_PROPERTY, body())
            .process(exchange -> {
                var result = exchange.getMessage().getBody(PortabilityResultNotification.class);
                send(exchange, new OperatorCommand(result.requestId(), result.msisdn(), Operation.RECEIVER_NOTIFICATION,
                        result.operadorReceptor(), mapper.valueToTree(result)));
                exchange.getMessage().setBody(result);
            });
    }

    private OperatorReply send(Exchange parent, OperatorCommand command) throws Exception {
        try {
            OperatorReply reply;
            if (!enabled) {
                // Exclusivo de tests HTTP aislados. En ejecución normal no hay fallback sin JMS.
                reply = executor.execute(command);
            } else {
                var response = producer.request("jms:queue:" + command.operation().queue
                        + "?requestTimeout={{app.messaging.request-timeout-ms}}&deliveryPersistent=true", exchange -> {
                    exchange.setPattern(ExchangePattern.InOut);
                    exchange.getMessage().setBody(mapper.writeValueAsString(command));
                    exchange.getMessage().setHeader("JMSCorrelationID", command.requestId());
                    exchange.getMessage().setHeader("operation", command.operation().name());
                });
                if (response.isFailed()) {
                    throw new MessageProcessingException("No se obtuvo respuesta JMS del consumidor.");
                }
                reply = mapper.readValue(response.getMessage().getBody(String.class), OperatorReply.class);
            }
            if (reply == null || !command.requestId().equals(reply.requestId()) || !reply.successful()) {
                throw new MessageProcessingException("La integración agotó los intentos; revisar eapn.error.");
            }
            return reply;
        } catch (Exception failure) {
            // Mantener los errores públicos existentes, sin propagar payloads/cadenas de causas JMS.
            if (!enabled) {
                Throwable cause = failure;
                while (cause != null) {
                    if (cause instanceof DonorNotificationException e) throw e;
                    if (cause instanceof DonorApprovalException e) throw e;
                    if (cause instanceof ReceiverNotificationException e) throw e;
                    cause = cause.getCause();
                }
            }
            String message = "Fallo técnico en " + command.operation() + "; revisar la cola de errores.";
            switch (command.operation()) {
                case PIN_NOTIFICATION -> throw new DonorNotificationException(message);
                case DONOR_APPROVAL -> throw new DonorApprovalException(message);
                case RECEIVER_NOTIFICATION -> throw new ReceiverNotificationException(message);
            }
            throw new IllegalStateException("Operación desconocida.");
        } finally {
            parent.getMessage().getHeaders().clear();
        }
    }
}
