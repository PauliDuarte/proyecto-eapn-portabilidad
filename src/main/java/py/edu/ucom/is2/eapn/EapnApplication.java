package py.edu.ucom.is2.eapn;

import java.time.ZoneId;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EapnApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(EapnApplication.class);
        application.addInitializers(context -> TimeZone.setDefault(TimeZone.getTimeZone(
                ZoneId.of(context.getEnvironment().getRequiredProperty("app.timezone")))));
        application.run(args);
    }
}
