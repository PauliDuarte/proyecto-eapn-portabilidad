package py.edu.ucom.is2.eapn.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresInitializationTest {
    @Test
    @SuppressWarnings("unchecked")
    void composeInitializesAllThreeTablesFromItsMountedScriptsOnAnEmptyDatabase() throws Exception {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yaml")));
        var services = (Map<String, Object>) compose.get("services");
        var postgres = (Map<String, Object>) services.get("postgresql");
        var volumes = (List<String>) postgres.get("volumes");
        var scripts = volumes.stream().filter(volume -> volume.contains(":/docker-entrypoint-initdb.d/"))
                .sorted(java.util.Comparator.comparing(volume -> volume.split(":")[1])).toList();
        assertThat(scripts).containsExactly(
                "./docker/postgres/init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro",
                "./docker/postgres/messaging.sql:/docker-entrypoint-initdb.d/02-messaging.sql:ro");
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:clean-init;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        // Alias de compatibilidad H2; los scripts reales se ejecutan sin modificarlos.
        jdbc.execute("CREATE DOMAIN TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        try {
            for (String script : scripts) {
                new ResourceDatabasePopulator(new FileSystemResource(script.split(":")[0])).execute(source);
            }
            for (String table : List.of("portability_request", "ported_number", "processed_message")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
