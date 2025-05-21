package choral.faulttolerance;

import java.sql.SQLException;

public interface Transaction {
    void commit(SQLTransaction trans) throws SQLException;

    void compensate(SQLTransaction trans);
}
