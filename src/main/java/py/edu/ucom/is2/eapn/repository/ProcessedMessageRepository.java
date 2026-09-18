package py.edu.ucom.is2.eapn.repository;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** La PK reclama una operación atómicamente, incluso entre instancias de EAPN. */
@Repository
public class ProcessedMessageRepository {
    private final JdbcTemplate jdbc;

    public ProcessedMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(String operation, String requestId) {
        try {
            jdbc.update("INSERT INTO processed_message (operation, request_id) VALUES (?, ?)", operation, requestId);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public Optional<String> findReply(String operation, String requestId) {
        return jdbc.query("SELECT reply FROM processed_message WHERE operation = ? AND request_id = ? AND reply IS NOT NULL",
                (rs, row) -> rs.getString("reply"), operation, requestId).stream().findFirst();
    }

    public void complete(String operation, String requestId, String reply) {
        if (jdbc.update("UPDATE processed_message SET reply = ? WHERE operation = ? AND request_id = ? AND reply IS NULL",
                reply, operation, requestId) != 1) {
            throw new IllegalStateException("No se pudo guardar la respuesta del mensaje.");
        }
    }

    public void release(String operation, String requestId) {
        jdbc.update("DELETE FROM processed_message WHERE operation = ? AND request_id = ? AND reply IS NULL", operation, requestId);
    }
}
