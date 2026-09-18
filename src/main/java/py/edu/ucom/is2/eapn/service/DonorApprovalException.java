package py.edu.ucom.is2.eapn.service;

/** Error técnico de integración, distinto de una decisión REJECTED del donante. */
public class DonorApprovalException extends RuntimeException {

    public DonorApprovalException(String message) {
        super(message);
    }
}
