package py.edu.ucom.is2.eapn.service;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.PortedNumber;
import py.edu.ucom.is2.eapn.model.dto.ConfirmPinResponse;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;
import py.edu.ucom.is2.eapn.repository.PortedNumberRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Prueba el proxy @Transactional con el gestor JDBC real y una conexión simulada. */
@SpringJUnitConfig(PortabilityFinalizationServiceTest.Config.class)
class PortabilityFinalizationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T01:30:00Z"), ZoneId.of("America/Asuncion"));
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    @Autowired private PortabilityFinalizationService service;
    @Autowired private PortabilityRequestRepository requests;
    @Autowired private PortedNumberRepository numbers;
    @Autowired private DataSource dataSource;
    @Autowired private Connection connection;

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean Connection connection() { return mock(Connection.class); }
        @Bean DataSource dataSource() { return mock(DataSource.class); }
        @Bean PortabilityRequestRepository requests() { return mock(PortabilityRequestRepository.class); }
        @Bean PortedNumberRepository numbers() { return mock(PortedNumberRepository.class); }
        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean PortabilityFinalizationService service(PortabilityRequestRepository requests, PortedNumberRepository numbers) {
            return new PortabilityFinalizationService(requests, numbers, CLOCK);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        reset(requests, numbers, dataSource, connection);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    @Test
    void insertAndCompletionRunInSameTransactionAndCommitBeforeReturn() throws Exception {
        when(numbers.insert(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        when(requests.markCompleted("req-1", NOW)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        var result = service.finish(request(), decision(PortabilityStatus.APPROVED, "Aprobada"));

        assertThat(result.estado()).isEqualTo(PortabilityStatus.COMPLETED);
        assertThat(result.fechaFinalizacion()).isEqualTo(NOW);
        assertThat(result.motivo()).isNull();
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.msisdn()).isEqualTo("+595971234567");
        assertThat(result.operadorDonante()).isEqualTo("Tigo");
        assertThat(result.operadorReceptor()).isEqualTo("Personal");
        var order = inOrder(connection, numbers, requests);
        order.verify(connection).setAutoCommit(false);
        order.verify(numbers).insert(new PortedNumber("+595971234567", "Tigo", "Personal", NOW));
        order.verify(requests).markCompleted("req-1", NOW);
        order.verify(connection).commit();
        verify(connection, never()).rollback();
        verifyNoMoreInteractions(numbers, requests);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void insertExceptionRollsBackAndDoesNotMarkCompleted() throws Exception {
        when(numbers.insert(any())).thenThrow(new DuplicateKeyException("Duplicated msisdn"));
        assertThatThrownBy(() -> service.finish(request(), decision(PortabilityStatus.APPROVED, "Aprobada")))
                .isInstanceOf(DuplicateKeyException.class);
        verifyNoInteractions(requests);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void zeroInsertedRowsRollsBackAndDoesNotMarkCompleted() throws Exception {
        assertThatThrownBy(() -> service.finish(request(), decision(PortabilityStatus.APPROVED, "Aprobada")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(requests);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void failedCompletionRollsBackPreviouslyInsertedNumber() throws Exception {
        when(numbers.insert(any())).thenReturn(1);
        when(requests.markCompleted("req-1", NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.finish(request(), decision(PortabilityStatus.APPROVED, "Aprobada")))
                .isInstanceOf(DataIntegrityViolationException.class);
        var order = inOrder(numbers, requests, connection);
        order.verify(numbers).insert(any());
        order.verify(requests).markCompleted("req-1", NOW);
        order.verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void completionExceptionAlsoRollsBack() throws Exception {
        when(numbers.insert(any())).thenReturn(1);
        when(requests.markCompleted("req-1", NOW)).thenThrow(new DataIntegrityViolationException("Update failed"));
        assertThatThrownBy(() -> service.finish(request(), decision(PortabilityStatus.APPROVED, "Aprobada")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void rejectionPreservesReasonAndDoesNotWriteBusinessData() {
        var result = service.finish(request(), decision(PortabilityStatus.REJECTED, "Datos no coinciden"));
        assertThat(result.estado()).isEqualTo(PortabilityStatus.REJECTED);
        assertThat(result.motivo()).isEqualTo("Datos no coinciden");
        assertThat(result.fechaFinalizacion()).isEqualTo(NOW);
        verifyNoInteractions(numbers, requests);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PortabilityStatus.class, names = {"APPROVED", "REJECTED"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsOtherDecisionsWithoutWrites(PortabilityStatus state) {
        assertThatThrownBy(() -> service.finish(request(), decision(state, "Mensaje")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(numbers, requests);
    }

    @Test
    void mismatchedIdDoesNotWrite() {
        assertThatThrownBy(() -> service.finish(request(), new ConfirmPinResponse("other-id", PortabilityStatus.APPROVED, "Aprobada")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(numbers, requests);
    }

    private ConfirmPinResponse decision(PortabilityStatus state, String reason) {
        return new ConfirmPinResponse("req-1", state, reason);
    }

    private PortabilityRequest request() {
        return new PortabilityRequest("req-1", "+595971234567", "12345", "Tigo", "Personal",
                PortabilityStatus.PENDING_DONOR, "000042", NOW.plusMinutes(10), 1,
                NOW.minusMinutes(10), NOW.minusMinutes(5), NOW, null, null);
    }
}
