package py.edu.ucom.is2.eapn.route;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;

import py.edu.ucom.is2.eapn.model.dto.PortabilityResultNotification;
import py.edu.ucom.is2.eapn.model.dto.ReceiverNotificationResponse;
import py.edu.ucom.is2.eapn.service.ReceiverNotificationException;

@Component
public class ReceiverNotificationRoute extends RouteBuilder {

    public static final String RESULT_PROPERTY = "receiverNotificationResult";
    private final ObjectMapper mapper;

    public ReceiverNotificationRoute(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void configure() {
        errorHandler(noErrorHandler());
        var responseMapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

        from("direct:notificar-resultado-receptor-http").routeId("notificar-resultado-receptor-http")
                .setProperty(RESULT_PROPERTY, body())
                .removeHeaders("*", "operator")
                .doTry()
                    .to("direct:seleccionar-receptor")
                    .marshal(new JacksonDataFormat(mapper, PortabilityResultNotification.class))
                    .to("{{app.wiremock.base-url}}/receptor/portability-result"
                            + "?httpMethod=POST&bridgeEndpoint=true&skipControlHeaders=true"
                            + "&throwExceptionOnFailure=false&followRedirects=false"
                            + "&connectTimeout=5000&connectionRequestTimeout=5000"
                            + "&responseTimeout={{app.receiver-notification.response-timeout-ms}}")
                    .process(exchange -> {
                        Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                        if (!Integer.valueOf(200).equals(code)) {
                            throw new ReceiverNotificationException("El receptor devolvió HTTP " + code + ".");
                        }
                    })
                    .unmarshal(new JacksonDataFormat(responseMapper, ReceiverNotificationResponse.class))
                    .process(exchange -> {
                        var result = exchange.getProperty(RESULT_PROPERTY, PortabilityResultNotification.class);
                        var response = exchange.getMessage().getBody(ReceiverNotificationResponse.class);
                        if (response == null || !result.requestId().equals(response.requestId())
                                || !"RECIBIDO".equals(response.estado())) {
                            throw new ReceiverNotificationException("Confirmación del receptor inválida o sin correlación.");
                        }
                        exchange.getMessage().setBody(result);
                    })
                .doCatch(Exception.class)
                    .process(exchange -> {
                        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                        exchange.getMessage().setBody(null);
                        if (failure instanceof ReceiverNotificationException notificationFailure) {
                            throw notificationFailure;
                        }
                        throw new ReceiverNotificationException("No se obtuvo una confirmación válida del receptor.");
                    })
                .end()
                .removeHeaders("*");
    }
}
