package py.edu.ucom.is2.eapn.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;
import py.edu.ucom.is2.eapn.model.dto.PortabilityErrorResponse;
import py.edu.ucom.is2.eapn.model.dto.PortabilityStatusResponse;
import py.edu.ucom.is2.eapn.service.PortabilityQueryService;

@Component
public class PortabilityQueryRoute extends RouteBuilder {
    private final PortabilityQueryService service;
    private final ObjectMapper mapper;

    public PortabilityQueryRoute(PortabilityQueryService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void configure() {
        onException(Exception.class).maximumRedeliveries(0).handled(true)
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .setBody(constant(new PortabilityErrorResponse("ERROR", "No se pudo consultar la solicitud.")))
            .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class));

        rest("/portabilidad/{id}").get().produces("application/json")
            .to("direct:query-portability");

        from("direct:query-portability").routeId("query-portability")
            .process(exchange -> exchange.getMessage().setBody(
                    service.findById(exchange.getMessage().getHeader("id", String.class)).orElse(null)))
            .removeHeaders("*")
            .choice()
                .when(body().isNull())
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setBody(constant(new PortabilityErrorResponse("RECHAZADA", "La solicitud de portabilidad no existe.")))
                    .marshal(new JacksonDataFormat(mapper, PortabilityErrorResponse.class))
                .otherwise()
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                    .marshal(new JacksonDataFormat(mapper, PortabilityStatusResponse.class))
            .end();
    }
}
