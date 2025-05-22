package dev.chords.warehouse.choreograhpy;

import java.io.Serializable;

import choral.channels.DiChannel;
import choral.channels.SymChannel;

import choral.reactive.ReactiveChannel;
import choral.faulttolerance.FaultSessionContext;

public class WarehouseOrder@(Warehouse, Payment, Loyalty) {

    private final FaultSessionContext@Warehouse ctx_warehouse;
    private final FaultSessionContext@Payment ctx_payment;
    private final FaultSessionContext@Loyalty ctx_loyalty;

    private final WarehouseService@Warehouse svc_warehouse;
    private final PaymentService@Payment svc_payment;
    private final LoyaltyService@Loyalty svc_loyalty;

    private final DiChannel@(Warehouse, Payment)<Serializable> ch_warehousePayment;
    private final DiChannel@(Payment, Loyalty)<Serializable> ch_paymentLoyalty;
    private final DiChannel@(Loyalty, Warehouse)<Serializable> ch_loyaltyWarehouse;

    public WarehouseOrder(
        FaultSessionContext@Warehouse ctx_warehouse,
        FaultSessionContext@Payment ctx_payment,
        FaultSessionContext@Loyalty ctx_loyalty,
        WarehouseService@Warehouse svc_warehouse,
        PaymentService@Payment svc_payment,
        LoyaltyService@Loyalty svc_loyalty
    ) {
        this.ctx_warehouse = ctx_warehouse;
        this.ctx_payment = ctx_payment;
        this.ctx_loyalty = ctx_loyalty;

        this.svc_warehouse = svc_warehouse;
        this.svc_payment = svc_payment;
        this.svc_loyalty = svc_loyalty;

        this.ch_warehousePayment = ReactiveChannel@(Warehouse, Payment).connect(
            ctx_warehouse, ctx_payment,
            "WAREHOUSE"@Payment, "PAYMENT"@Warehouse
        );

        this.ch_paymentLoyalty = ReactiveChannel@(Payment, Loyalty).connect(
            ctx_payment, ctx_loyalty,
            "PAYMENT"@Loyalty, "LOYALTY"@Payment
        );

        this.ch_loyaltyWarehouse = ReactiveChannel@(Loyalty, Warehouse).connect(
            ctx_loyalty, ctx_warehouse,
            "LOYALTY"@Warehouse, "WAREHOUSE"@Loyalty
        );
    }

    public void orderFulfillment() {
        ctx_warehouse.log("Starting order fulfillment"@Warehouse);
        ctx_warehouse.transaction(
            svc_warehouse.checkItemInStockAndReserveForOrder()
        );
        ctx_warehouse.log("Successfully checked stock and reserved item for order"@Warehouse);

        ch_warehousePayment.<Boolean>com(true@Warehouse);

        ctx_payment.log("Starting order fulfillment"@Payment);
        ctx_payment.transaction(
            svc_payment.takeMoneyFromCustomer()
        );
        ctx_payment.log("Successfully made payment for customer"@Payment);

        ch_paymentLoyalty.<Boolean>com(true@Payment);

        ctx_loyalty.log("Starting order fulfillment"@Loyalty);
        ctx_loyalty.transaction(
            svc_loyalty.awardPointsToCustomer()
        );
        ctx_loyalty.log("Successfully awarded points to customer"@Loyalty);

        ch_loyaltyWarehouse.<Boolean>com(true@Loyalty);

        ctx_warehouse.log("Order fulfillment completed successfully"@Warehouse);
    }
}
