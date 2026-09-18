package py.edu.ucom.is2.eapn.route;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.dto.DonorApprovalRequest;
import py.edu.ucom.is2.eapn.model.dto.DonorApprovalResponse;
import py.edu.ucom.is2.eapn.service.DonorApprovalException;

@Component
public class DonorApprovalRoute extends RouteBuilder {

    public static final String REQUEST_PROPERTY = "donorApprovalRequest";
    private final ObjectMapper mapper;

    public DonorApprovalRoute(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void configure() {
        errorHandler(noErrorHandler());
        ObjectMapper responseMapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        responseMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);

        from("direct:solicitar-aprobacion-donante").routeId("solicitar-aprobacion-donante")
                .setProperty(REQUEST_PROPERTY, body())
                .removeHeaders("*")
                .doTry()
                    .process(exchange -> {
                        var request = exchange.getProperty(REQUEST_PROPERTY, PortabilityRequest.class);
                        exchange.getMessage().setHeader("X-Operador-Donante", request.operadorDonante());
                        exchange.getMessage().setBody(new DonorApprovalRequest(request.id(), request.msisdn(),
                                request.documentoTitular(), request.operadorReceptor()));
                    })
                    .marshal(new JacksonDataFormat(mapper, DonorApprovalRequest.class))
                    .to("{{app.wiremock.base-url}}/operador/portability-approval"
                            + "?httpMethod=POST&bridgeEndpoint=true&skipControlHeaders=true"
                            + "&throwExceptionOnFailure=false&followRedirects=false"
                            + "&connectTimeout=5000&connectionRequestTimeout=5000"
                            + "&responseTimeout={{app.donor-approval.response-timeout-ms}}")
                    .process(exchange -> {
                        Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                        if (!Integer.valueOf(200).equals(code)) {
                            throw new DonorApprovalException("El donante devolvió HTTP " + code + " al consultar aprobación.");
                        }
                    })
                    .unmarshal(new JacksonDataFormat(responseMapper, DonorApprovalResponse.class))
                    .process(exchange -> {
                        var request = exchange.getProperty(REQUEST_PROPERTY, PortabilityRequest.class);
                        var decision = exchange.getMessage().getBody(DonorApprovalResponse.class);
                        if (decision == null || !request.id().equals(decision.requestId())) {
                            throw new DonorApprovalException("Respuesta de aprobación sin correlación válida.");
                        }
                        if (!"APPROVED".equals(decision.estado()) && !"REJECTED".equals(decision.estado())) {
                            throw new DonorApprovalException("El donante devolvió un estado desconocido.");
                        }
                        if ("REJECTED".equals(decision.estado())
                                && (decision.motivo() == null || decision.motivo().isBlank())) {
                            throw new DonorApprovalException("El donante rechazó sin indicar un motivo.");
                        }
                    })
                .doCatch(Exception.class)
                    .process(exchange -> {
                        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                        exchange.getMessage().setBody(null);
                        if (failure instanceof DonorApprovalException approvalFailure) {
                            throw approvalFailure;
                        }
                        // No exponer cuerpos del donante ni datos personales en el error técnico.
                        throw new DonorApprovalException("No se obtuvo una respuesta válida de aprobación del donante.");
                    })
                .end()
                .removeHeaders("*");
    }
}
