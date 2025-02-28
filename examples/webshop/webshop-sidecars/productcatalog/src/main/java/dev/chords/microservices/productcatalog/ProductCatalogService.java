package dev.chords.microservices.productcatalog;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import choral.reactive.ChannelConfigurator;
import choral.reactive.tracing.JaegerConfiguration;
import dev.chords.choreographies.Cart;
import dev.chords.choreographies.Money;
import dev.chords.choreographies.OrderItem;
import dev.chords.choreographies.OrderItems;
import dev.chords.choreographies.Product;
import dev.chords.choreographies.Products;
import hipstershop.Demo;
import hipstershop.ProductCatalogServiceGrpc;
import hipstershop.Demo.Empty;
import hipstershop.Demo.GetProductRequest;
import hipstershop.Demo.ListProductsResponse;
import hipstershop.Demo.SearchProductsRequest;
import hipstershop.ProductCatalogServiceGrpc.ProductCatalogServiceFutureStub;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

public class ProductCatalogService implements dev.chords.choreographies.ProductCatalogService {

    protected ManagedChannel channel;
    protected ProductCatalogServiceFutureStub connection;
    protected Tracer tracer;

    public ProductCatalogService(InetSocketAddress address, OpenTelemetrySdk telemetry) {
        channel = ChannelConfigurator.makeChannel(address, telemetry);

        this.connection = ProductCatalogServiceGrpc.newFutureStub(channel);
        this.tracer = telemetry.getTracer(JaegerConfiguration.TRACER_NAME);
    }

    @Override
    public Products listProducts() {
        System.out.println("[PRODUCT_CATALOG] List products");

        Span span = tracer.spanBuilder("ProductCatalogService.listProducts").startSpan();

        try (Scope scope = span.makeCurrent()) {

            ListProductsResponse response = connection.listProducts(Empty.getDefaultInstance())
                    .get(10, TimeUnit.SECONDS);

            if (span != null)
                span.addEvent("Request sent, restructuring products");

            List<Product> products = response.getProductsList().stream().map(prod -> new Product(
                    prod.getId(),
                    prod.getName(),
                    prod.getDescription(),
                    prod.getPicture(),
                    new Money(prod.getPriceUsd().getCurrencyCode(),
                            (int) prod.getPriceUsd().getUnits(),
                            prod.getPriceUsd().getNanos()),
                    prod.getCategoriesList())).toList();

            return new Products(new ArrayList<>(products));
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    @Override
    public Product getProduct(String productID) {
        System.out.println("[PRODUCT_CATALOG] Get product: productID=" + productID);

        Span span = tracer.spanBuilder("ProductCatalogService.getProduct")
                .setAttribute("request.productID", productID)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {

            GetProductRequest request = GetProductRequest.newBuilder().setId(productID).build();

            Demo.Product p = connection.getProduct(request).get(5, TimeUnit.SECONDS);

            return convertProduct(p);
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    @Override
    public Products searchProducts(String query) {
        System.out.println("[PRODUCT_CATALOG] Search products: query=" + query);

        Span span = tracer.spanBuilder("ProductCatalogService.searchProducts")
                .setAttribute("request.query", query)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {

            SearchProductsRequest request = SearchProductsRequest.newBuilder().setQuery(query).build();
            List<Product> products = connection
                    .searchProducts(request)
                    .get(5, TimeUnit.SECONDS)
                    .getResultsList()
                    .stream()
                    .map(p -> convertProduct(p)).toList();

            return new Products(new ArrayList<>(products));
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    @Override
    public OrderItems lookupCartPrices(Cart cart) {
        System.out.println("[PRODUCT_CATALOG] Lookup cart prices");

        Span span = tracer.spanBuilder("ProductCatalogService.lookupCartPrices").startSpan();

        try (Scope scope = span.makeCurrent()) {

            List<OrderItem> products = cart.items.stream()
                    .map(item -> new OrderItem(item, getProduct(item.product_id).priceUSD)).toList();

            return new OrderItems(new ArrayList<>(products));
        } catch (Exception e) {
            span.setAttribute("error", true);
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    private Product convertProduct(Demo.Product p) {
        Money money = new Money(
                p.getPriceUsd().getCurrencyCode(),
                (int) p.getPriceUsd().getUnits(),
                p.getPriceUsd().getNanos());

        List<String> categories = p.getCategoriesList();

        return new Product(p.getId(), p.getName(), p.getDescription(), p.getPicture(), money, categories);
    }

}
