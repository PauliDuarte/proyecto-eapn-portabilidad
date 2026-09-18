package py.edu.ucom.is2.eapn.route;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.PinNotificationRequest;
import py.edu.ucom.is2.eapn.model.dto.PinNotificationResponse;

@Component
public class PinNotificationRoute extends RouteBuilder {

    public static final String REQUEST_PROPERTY = "pinNotificationRequest";
    private final ObjectMapper mapper;

    public PinNotificationRoute(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void configure() {
        // Propagar al flujo REST; no reintentar ni registrar cuerpos que contienen PIN.
        errorHandler(noErrorHandler());

        from("direct:enviar-pin-operador").routeId("enviar-pin-operador")
                .setProperty(REQUEST_PROPERTY, body())
                .removeHeaders("*")
                .doTry()
                    .process(exchange -> {
                        var request = exchange.getProperty(REQUEST_PROPERTY, PortabilityRequest.class);
                        exchange.getMessage().setHeader("X-Operador-Donante", request.operadorDonante());
                        exchange.getMessage().setBody(new PinNotificationRequest(request.id(), request.msisdn(),
                                request.documentoTitular(), request.pin()));
                    })
                    .marshal(new JacksonDataFormat(mapper, PinNotificationRequest.class))
                    .to("{{app.wiremock.base-url}}/operador/pin-notification"
                            + "?httpMethod=POST&bridgeEndpoint=true&skipControlHeaders=true"
                            + "&throwExceptionOnFailure=false&followRedirects=false"
                            + "&connectTimeout=5000&connectionRequestTimeout=5000&responseTimeout=5000")
                    .process(exchange -> {
                        Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                        if (!Integer.valueOf(200).equals(code)) {
                            throw new DonorNotificationException("El operador donante devolvió HTTP " + code + ".");
                        }
                    })
                    .unmarshal(new JacksonDataFormat(mapper, PinNotificationResponse.class))
                    .process(exchange -> {
                        var request = exchange.getProperty(REQUEST_PROPERTY, PortabilityRequest.class);
                        var confirmation = exchange.getMessage().getBody(PinNotificationResponse.class);
                        if (confirmation == null || !request.id().equals(confirmation.requestId())
                                || !"PIN_ENVIADO".equals(confirmation.estado())) {
                            throw new DonorNotificationException("Confirmación del donante inválida o sin correlación.");
                        }
                        exchange.getMessage().setBody(request);
                    })
                .doCatch(Exception.class)
                    .process(exchange -> {
                        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                        // El cuerpo HTTP podría contener el PIN; no propagar su contenido ni su causa.
                        exchange.getMessage().setBody(null);
                        if (failure instanceof DonorNotificationException notificationFailure) {
                            throw notificationFailure;
                        }
                        throw new DonorNotificationException("No se obtuvo una confirmación válida del operador donante.");
                    })
                .end()
                .removeHeaders("*");
    }
}
