package py.edu.ucom.is2.eapn.service;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import py.edu.ucom.is2.eapn.model.PortabilityRequest;
import py.edu.ucom.is2.eapn.model.PortabilityStatus;
import py.edu.ucom.is2.eapn.model.dto.DonorApprovalResponse;
import py.edu.ucom.is2.eapn.repository.PortabilityRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DonorApprovalServiceTest {

    @Mock
    private PortabilityRequestRepository repository;
    private DonorApprovalService service;

    @BeforeEach
    void setUp() {
        service = new DonorApprovalService(repository);
    }

    @Test
    void beginsFromConfirmedWithoutChangingPinDatesOrAttempts() {
        var confirmed = request(PortabilityStatus.CONFIRMED);
        when(repository.markPendingDonor(confirmed.id())).thenReturn(1);
        var pending = service.begin(confirmed);

        assertThat(pending.estado()).isEqualTo(PortabilityStatus.PENDING_DONOR);
        assertThat(pending).usingRecursiveComparison().ignoringFields("estado").isEqualTo(confirmed);
        verify(repository).markPendingDonor(confirmed.id());
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = PortabilityStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void requiresConfirmedState(PortabilityStatus state) {
        assertThatThrownBy(() -> service.begin(request(state))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void failedPendingWriteDoesNotReturnPendingRequest() {
        assertThatThrownBy(() -> service.begin(request(PortabilityStatus.CONFIRMED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvedDecisionOnlyWritesApproved() {
        when(repository.approveByDonor("req-1")).thenReturn(1);
        var response = service.applyDecision(new DonorApprovalResponse("req-1", "APPROVED", null));
        assertThat(response.id()).isEqualTo("req-1");
        assertThat(response.estado()).isEqualTo(PortabilityStatus.APPROVED);
        verify(repository).approveByDonor("req-1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectedDecisionOnlyWritesRejectedAndOriginalReason() {
        when(repository.rejectByDonor("req-1", "Datos del titular no coinciden")).thenReturn(1);
        var response = service.applyDecision(new DonorApprovalResponse("req-1", "REJECTED", "Datos del titular no coinciden"));
        assertThat(response.estado()).isEqualTo(PortabilityStatus.REJECTED);
        assertThat(response.mensaje()).isEqualTo("Datos del titular no coinciden");
        verify(repository).rejectByDonor("req-1", "Datos del titular no coinciden");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void cannotApplyCompletedOrUnknownState() {
        assertThatThrownBy(() -> service.applyDecision(new DonorApprovalResponse("req-1", "COMPLETED", null)))
                .isInstanceOf(DonorApprovalException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void failedApprovalWriteDoesNotReturnSuccess() {
        assertThatThrownBy(() -> service.applyDecision(new DonorApprovalResponse("req-1", "APPROVED", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedRejectionWriteDoesNotReturnBusinessRejection() {
        assertThatThrownBy(() -> service.applyDecision(new DonorApprovalResponse("req-1", "REJECTED", "Motivo")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PortabilityRequest request(PortabilityStatus status) {
        var now = OffsetDateTime.parse("2026-09-18T10:00:00-03:00");
        return new PortabilityRequest("req-1", "+595971234567", "12345", "Tigo", "Personal", status,
                "000042", now.plusMinutes(10), 1, now.minusMinutes(10), now.minusMinutes(5), now, null, null);
    }
}
