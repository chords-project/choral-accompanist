package dev.chords.warehouse.warehouse;

import choral.faulttolerance.SQLTransaction;
import choral.faulttolerance.Transaction;

import java.sql.SQLException;

public class WarehouseService implements dev.chords.warehouse.choreograhpy.WarehouseService {
    @Override
    public Transaction checkItemInStockAndReserveForOrder() {
        System.out.println("- Warehouse make transaction: checkItemInStockAndReserveForOrder");

        return new Transaction() {
            @Override
            public boolean commit(SQLTransaction trans) {
                System.out.println("- Warehouse commit transaction: checkItemInStockAndReserveForOrder");
                return true;
            }

            @Override
            public void compensate(SQLTransaction trans) {
                System.out.println("- Warehouse compensate transaction: checkItemInStockAndReserveForOrder");
            }
        };
    }

    @Override
    public Transaction packageAndSendOrder() {
        System.out.println("- Warehouse make transaction: packageAndSendOrder");

        return new Transaction() {
            @Override
            public boolean commit(SQLTransaction trans) throws SQLException {
                System.out.println("- Warehouse commit transaction: packageAndSendOrder");
                return true;
            }

            @Override
            public void compensate(SQLTransaction trans) {
                System.out.println("- Warehouse compensate transaction: packageAndSendOrder");
            }
        };
    }
}
