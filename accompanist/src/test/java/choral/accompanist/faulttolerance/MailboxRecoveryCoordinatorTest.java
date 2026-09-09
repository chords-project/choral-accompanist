package choral.accompanist.faulttolerance;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailboxRecoveryCoordinatorTest {
    @Test void categorizesGrpcFailuresByStatusCode() {
        var error = new ExecutionException(Status.UNAVAILABLE.asRuntimeException());

        assertEquals("grpc.unavailable", MailboxRecoveryCoordinator.failureCategory(error));
    }

    @Test void categorizesAckPersistenceFailures() {
        var error = new ExecutionException(new SQLException("database unavailable"));

        assertEquals("persistence", MailboxRecoveryCoordinator.failureCategory(error));
    }

    @Test void fallsBackToTheRootExceptionType() {
        var error = new ExecutionException(new IllegalStateException("unexpected"));

        assertEquals("IllegalStateException", MailboxRecoveryCoordinator.failureCategory(error));
    }
}
