package choral.faulttolerance;

import java.sql.SQLException;

public interface Transaction {
    boolean commit(SQLTransaction trans) throws SQLException;

    void compensate(SQLTransaction trans);
}
