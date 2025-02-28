package dev.chords.choreographies;

import java.io.Serializable;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import choral.channels.DiChannel;
import choral.channels.SymChannel;
//import choral.channels.AsyncDiChannel;
//import choral.channels.AsyncSymChannel;
import choral.channels.Future;

public class ChorPlaceOrder@(Client, Cart, ProductCatalog, Currency, Payment, Shipping, Email) {

    private ClientService@Client clientSvc;
    private CartService@Cart cartSvc;
    private ProductCatalogService@ProductCatalog productCatalogSvc;
    private CurrencyService@Currency currencySvc;
    private ShippingService@Shipping shippingSvc;
    private PaymentService@Payment paymentSvc;
    private EmailService@Email emailSvc;

    private SymChannel@(Client, Currency)<Serializable> ch_clientCurrency;
    private SymChannel@(Client, Shipping)<Serializable> ch_clientShipping;
    private SymChannel@(Client, Payment)<Serializable> ch_clientPayment;
    private SymChannel@(Client, Email)<Serializable> ch_clientEmail;

    private DiChannel@(Client, Cart)<Serializable> ch_clientCart;

    private DiChannel@(Cart, ProductCatalog)<Serializable> ch_cartProduct;
    private DiChannel@(Cart, Shipping)<Serializable> ch_cartShipping;

    private DiChannel@(ProductCatalog, Currency)<Serializable> ch_productCurrency;

    private DiChannel@(Shipping, Currency)<Serializable> ch_shippingCurrency;

    public ChorPlaceOrder(
        ClientService@Client clientSvc,
        CartService@Cart cartSvc,
        ProductCatalogService@ProductCatalog productCatalogSvc,
        CurrencyService@Currency currencySvc,
        ShippingService@Shipping shippingSvc,
        PaymentService@Payment paymentSvc,
        EmailService@Email emailSvc,
        SymChannel@(Client, Currency)<Serializable> ch_clientCurrency,
        SymChannel@(Client, Shipping)<Serializable> ch_clientShipping,
        SymChannel@(Client, Payment)<Serializable> ch_clientPayment,
        SymChannel@(Client, Email)<Serializable> ch_clientEmail,
        DiChannel@(Client, Cart)<Serializable> ch_clientCart,
        DiChannel@(Cart, ProductCatalog)<Serializable> ch_cartProduct,
        DiChannel@(Cart, Shipping)<Serializable> ch_cartShipping,
        DiChannel@(ProductCatalog, Currency)<Serializable> ch_productCurrency,
        DiChannel@(Shipping, Currency)<Serializable> ch_shippingCurrency
    ) {
        this.clientSvc = clientSvc;
        this.cartSvc = cartSvc;
        this.productCatalogSvc = productCatalogSvc;
        this.currencySvc = currencySvc;
        this.shippingSvc = shippingSvc;
        this.paymentSvc = paymentSvc;
        this.emailSvc = emailSvc;

        this.ch_clientCart = ch_clientCart;
        this.ch_clientCurrency = ch_clientCurrency;
        this.ch_clientShipping = ch_clientShipping;
        this.ch_clientPayment = ch_clientPayment;
        this.ch_clientEmail = ch_clientEmail;

        this.ch_cartProduct = ch_cartProduct;
        this.ch_cartShipping = ch_cartShipping;

        this.ch_productCurrency = ch_productCurrency;

        this.ch_shippingCurrency = ch_shippingCurrency;
    }

    public OrderResult@Client placeOrder(ReqPlaceOrder@Client req) {
        System@Client.out.println("Starting place order choreography: user_id="@Client + req.user_id + " user_currency="@Client + req.user_currency);

        SerializableString@Cart userID_cart = ch_clientCart.<SerializableString>com(new SerializableString@Client(req.user_id));
        SerializableString@Currency userCurrency = ch_clientCurrency.<SerializableString>com(new SerializableString@Client(req.user_currency));
        Address@Shipping shippingAddress = ch_clientShipping.<Address>com(req.address);
        CreditCardInfo@Payment creditCartInfo = ch_clientPayment.<CreditCardInfo>com(req.credit_card);
        SerializableString@Email emailAddress = ch_clientEmail.<SerializableString>com(new SerializableString@Client(req.email));

        // Fetch user cart items
        Cart@Cart userCart = cartSvc.getCart(userID_cart.string);
        Cart@ProductCatalog cart_pc = ch_cartProduct.<Cart>com(userCart);
        Cart@Shipping cart_shipping = ch_cartShipping.<Cart>com(userCart);

        cartSvc.emptyCart(userID_cart.string);

        // Lookup cart item prices
        OrderItems@ProductCatalog cartPrices = productCatalogSvc.lookupCartPrices(cart_pc);
        OrderItems@Currency cartPrices_currency = ch_productCurrency.<OrderItems>com(cartPrices);

        // Convert currency of products
        OrderItems@Currency orderItems = currencySvc.convertProducts(cartPrices_currency, userCurrency.string);
        OrderItems@Client orderItems_client = ch_clientCurrency.<OrderItems>com(orderItems);

        // Calculate shipping
        Money@Shipping shippingCost = shippingSvc.getQuote(shippingAddress, cart_shipping);
        Money@Currency shippingCost_currency = ch_shippingCurrency.<Money>com(shippingCost);

        // Convert shipping currency
        Money@Currency convertedShippingCost = currencySvc.convert(shippingCost_currency, userCurrency.string);
        Money@Client shippingCost_client = ch_clientCurrency.<Money>com(convertedShippingCost);

        // Ship order
        String@Shipping trackingID = shippingSvc.shipOrder(shippingAddress, cart_shipping);
        SerializableString@Client trackingID_client = ch_clientShipping.<SerializableString>com(new SerializableString@Shipping(trackingID));

        // Charge card
        Money@Client totalPrice = clientSvc.totalPrice(orderItems_client, shippingCost_client);
        Money@Payment chargePrice = ch_clientPayment.<Money>com(totalPrice);
        String@Payment txID = paymentSvc.charge(chargePrice, creditCartInfo);

        // Send back charge confirmation
        Boolean@Client chargeSuccess = ch_clientPayment.<Boolean>com(true@Payment);

        // Bundle response at client
        UUID@Client orderID = UUID@Client.randomUUID();

        OrderResult@Client orderResult = new OrderResult@Client(
            orderID.toString(),
            trackingID_client.string,
            shippingCost_client,
            req.address,
            orderItems_client
        );

        // Send confirmation email
        OrderResult@Email orderResult_email = ch_clientEmail.<OrderResult>com(orderResult);
        emailSvc.sendOrderConfirmation(emailAddress.string, orderResult_email);
        Boolean@Client emailSent = ch_clientEmail.<Boolean>com(true@Email);

        return orderResult;
    }
}
