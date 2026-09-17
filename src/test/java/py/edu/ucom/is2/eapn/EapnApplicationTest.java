package py.edu.ucom.is2.eapn;

import javax.sql.DataSource;
import jakarta.jms.ConnectionFactory;

import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@CamelSpringBootTest
@SpringBootTest(properties = "camel.springboot.main-run-controller=false")
class EapnApplicationTest {

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @Autowired
    private CamelContext camelContext;

    @Test
    void contextLoadsWithoutExternalInfrastructure() {
        assertThat(camelContext.isStarted()).isTrue();
        assertThat(camelContext.getRoutes()).isEmpty();
        verifyNoInteractions(dataSource, connectionFactory);
    }
}
