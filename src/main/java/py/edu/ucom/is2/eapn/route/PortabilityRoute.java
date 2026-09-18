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
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import py.edu.ucom.is2.eapn.messaging.StateEventPublisher;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.CreatePortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.PortabilityErrorResponse;
import py.edu.ucom.is2.eapn.model.dto.PortabilityResponse;
import py.edu.ucom.is2.eapn.service.PortabilityService;
import py.edu.ucom.is2.eapn.service.PinGenerationService;
import py.edu.ucom.is2.eapn.service.RequestValidationException;

@Component
public class PortabilityRoute extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(PortabilityRoute.class);
    private final PortabilityService service;
    private final PinGenerationService pinService;
    private final ObjectMapper mapper;
    private final StateEventPublisher events;

    public PortabilityRoute(PortabilityService service, PinGenerationService pinService, ObjectMapper mapper, StateEventPublisher events) {
        this.service = service;
        this.pinService = pinService;
        this.mapper = mapper;
        this.events = events;
    }

    @Override
    public void configure() {
        ObjectMapper requestMapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        requestMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);

        onException(DonorNotificationException.class)
                .maximumRedeliveries(0)
                .handled(true)
                .process(exchange -> {
                    var request = exchange.getProperty(PinNotificationRoute.REQUEST_PROPERTY, PortabilityRequest.class);
                    var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, DonorNotificationException.class);
                    LOG.warn("Notificación PIN fallida: request_id={}, donante={}, motivo={}",
                            request.id(), request.operadorDonante(), failure.getMessage());
                    pinService.recordNotificationFailure(request.id(), failure.getMessage());
                    exchange.getMessage().setBody(new PortabilityErrorResponse("ERROR",
                            "No se pudo confirmar el envío del PIN. Solicitud: " + request.id()));
                })
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(502))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(RequestValidationException.class)
                .handled(true)
                .process(exchange -> {
                    var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, RequestValidationException.class);
                    exchange.getMessage().setBody(new PortabilityErrorResponse("RECHAZADA", failure.getMessage()));
                })
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(JsonProcessingException.class)
                .handled(true)
                .setBody(constant(new PortabilityErrorResponse("RECHAZADA",
                        "El cuerpo debe ser un objeto JSON válido con campos de texto.")))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        onException(Exception.class)
                .handled(true)
                .setBody(constant(new PortabilityErrorResponse("ERROR", "No se pudo registrar la solicitud.")))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
                .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        // Binding explícito dentro de la ruta para manejar también errores de JSON.
        restConfiguration().component("platform-http").bindingMode(RestBindingMode.off);
        rest("/portabilidad")
                .post().consumes("application/json").produces("application/json")
                .to("direct:create-portability");

        from("direct:create-portability").routeId("create-portability")
                .process(exchange -> {
                    if (exchange.getMessage().getBody() == null) {
                        throw new RequestValidationException("El cuerpo de la solicitud es obligatorio.");
                    }
                })
                .unmarshal(new JacksonDataFormat(requestMapper, CreatePortabilityRequest.class))
                .process(exchange -> {
                    var input = exchange.getMessage().getBody(CreatePortabilityRequest.class);
                    var request = service.create(input);
                    events.publish(request);
                    exchange.getMessage().setBody(request);
                })
                .process(exchange -> exchange.getMessage().setBody(
                        pinService.generate(exchange.getMessage().getBody(PortabilityRequest.class))))
                .process(exchange -> events.publish(exchange.getMessage().getBody(PortabilityRequest.class)))
                .to("direct:enviar-pin-operador")
                .process(exchange -> exchange.getMessage().setBody(
                        PortabilityResponse.from(exchange.getMessage().getBody(PortabilityRequest.class))))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(201))
                .marshal(new JacksonDataFormat(mapper, PortabilityResponse.class));
    }
}
