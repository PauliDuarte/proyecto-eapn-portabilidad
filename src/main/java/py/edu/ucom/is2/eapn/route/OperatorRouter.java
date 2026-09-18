package py.edu.ucom.is2.eapn.route;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;
import py.edu.ucom.is2.eapn.messaging.MessageProcessingException;

@Component
public class OperatorRouter extends RouteBuilder {
    @Override
    public void configure() {
        errorHandler(noErrorHandler());
        for (String role : new String[] {"donante", "receptor"}) {
            String header = role.equals("donante") ? "X-Operador-Donante" : "X-Operador-Receptor";
            from("direct:seleccionar-" + role).routeId("seleccionar-" + role)
                .choice()
                    .when(header("operator").isEqualTo("Tigo")).setHeader(header, constant("Tigo"))
                    .when(header("operator").isEqualTo("Personal")).setHeader(header, constant("Personal"))
                    .when(header("operator").isEqualTo("Claro")).setHeader(header, constant("Claro"))
                    .when(header("operator").isEqualTo("Vox")).setHeader(header, constant("Vox"))
                    .otherwise().throwException(new MessageProcessingException("Operador de integración desconocido."))
                .end()
                .removeHeader("operator");
        }
    }
}
