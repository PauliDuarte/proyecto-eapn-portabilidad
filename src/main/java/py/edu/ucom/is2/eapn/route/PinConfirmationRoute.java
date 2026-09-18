package py.edu.ucom.is2.eapn.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.DonorApprovalResponse;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinRequest;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinResponse;
import py.edu.ucom.is2.eapn.model.dto.PortabilityErrorResponse;
import py.edu.ucom.is2.eapn.model.dto.PortabilityResultNotification;
import py.edu.ucom.is2.eapn.service.PinConfirmationException;
import py.edu.ucom.is2.eapn.service.PinConfirmationService;
import py.edu.ucom.is2.eapn.service.DonorApprovalException;
import py.edu.ucom.is2.eapn.service.DonorApprovalService;
import py.edu.ucom.is2.eapn.service.PortabilityFinalizationService;
import py.edu.ucom.is2.eapn.service.ReceiverNotificationException;

@Component
public class PinConfirmationRoute extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(PinConfirmationRoute.class);
    private final PinConfirmationService service;
    private final DonorApprovalService approvalService;
    private final PortabilityFinalizationService finalizationService;
    private final ObjectMapper mapper;

    public PinConfirmationRoute(PinConfirmationService service, DonorApprovalService approvalService,
            PortabilityFinalizationService finalizationService, ObjectMapper mapper) {
        this.service = service;
        this.approvalService = approvalService;
        this.finalizationService = finalizationService;
        this.mapper = mapper;
    }

    @Override
    public void configure() {
        ObjectMapper requestMapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        requestMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);

        onException(ReceiverNotificationException.class)
                .maximumRedeliveries(0).handled(true)
                .process(exchange -> {
                    var result = exchange.getProperty(ReceiverNotificationRoute.RESULT_PROPERTY, PortabilityResultNotification.class);
                    var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, ReceiverNotificationException.class);
                    LOG.warn("Notificación final fallida: request_id={}, receptor={}, estado={}, motivo={}",
                            result.requestId(), result.operadorReceptor(), result.estado(), failure.getMessage());
                    exchange.getMessage().setBody(new PortabilityErrorResponse("ERROR",
                            "No se pudo confirmar la notificación al receptor. Solicitud " + result.requestId()
                                    + " conserva estado " + result.estado() + "."));
                })
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(502))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(DonorApprovalException.class)
                .maximumRedeliveries(0).handled(true)
                .process(exchange -> {
                    var request = exchange.getProperty(DonorApprovalRoute.REQUEST_PROPERTY, PortabilityRequest.class);
                    var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, DonorApprovalException.class);
                    LOG.warn("Consulta al donante fallida: request_id={}, donante={}, motivo={}",
                            request.id(), request.operadorDonante(), failure.getMessage());
                    exchange.getMessage().setBody(new PortabilityErrorResponse("ERROR",
                            "No se pudo obtener la decisión del donante. Solicitud pendiente: " + request.id()));
                })
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(502))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(PinConfirmationException.class)
                .maximumRedeliveries(0).handled(true)
                .process(exchange -> {
                    var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, PinConfirmationException.class);
                    int status = switch (failure.reason()) {
                        case NOT_FOUND -> 404;
                        case INVALID_STATE, MISSING_PIN, STATE_CHANGED -> 409;
                        case MALFORMED_PIN, INCORRECT_PIN, EXPIRED_PIN -> 400;
                    };
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
                    exchange.getMessage().setBody(new PortabilityErrorResponse("RECHAZADA", failure.getMessage()));
                })
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(JsonProcessingException.class)
                .maximumRedeliveries(0).handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setBody(constant(new PortabilityErrorResponse("RECHAZADA",
                        "El cuerpo debe ser un objeto JSON válido con el campo pin de texto.")))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(Exception.class)
                .maximumRedeliveries(0).handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
                .setBody(constant(new PortabilityErrorResponse("ERROR", "No se pudo procesar la confirmación del PIN.")))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        rest("/portabilidad/{id}/pin/confirmar")
                .post().consumes("application/json").produces("application/json")
                .to("direct:confirm-portability-pin");

        from("direct:confirm-portability-pin").routeId("confirm-portability-pin")
                .process(exchange -> {
                    if (exchange.getMessage().getBody() == null) {
                        throw new PinConfirmationException(PinConfirmationException.Reason.MALFORMED_PIN,
                                "El cuerpo de la solicitud es obligatorio.");
                    }
                })
                .unmarshal(new JacksonDataFormat(requestMapper, ConfirmPinRequest.class))
                .process(exchange -> {
                    String id = exchange.getMessage().getHeader("id", String.class);
                    var input = exchange.getMessage().getBody(ConfirmPinRequest.class);
                    exchange.getMessage().setBody(service.confirm(id, input));
                })
                .process(exchange -> exchange.getMessage().setBody(
                        approvalService.begin(exchange.getMessage().getBody(PortabilityRequest.class))))
                .to("direct:solicitar-aprobacion-donante")
                .process(exchange -> exchange.getMessage().setBody(
                        approvalService.applyDecision(exchange.getMessage().getBody(DonorApprovalResponse.class))))
                .process(exchange -> exchange.getMessage().setBody(finalizationService.finish(
                        exchange.getProperty(DonorApprovalRoute.REQUEST_PROPERTY, PortabilityRequest.class),
                        exchange.getMessage().getBody(ConfirmPinResponse.class))))
                // finish() es un bean transaccional: al retornar, el commit ya terminó.
                .to("direct:notificar-resultado-receptor")
                .process(exchange -> exchange.getMessage().setBody(
                        ConfirmPinResponse.fromResult(exchange.getMessage().getBody(PortabilityResultNotification.class))))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .marshal(new JacksonDataFormat(mapper, ConfirmPinResponse.class));
    }
}
