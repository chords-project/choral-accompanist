package choral.reactive.tracing;

import choral.reactive.connection.Message;
import io.opentelemetry.context.propagation.TextMapGetter;

@SuppressWarnings("null")
public class HeaderTextMapGetter implements TextMapGetter<Message> {

    @Override
    public Iterable<String> keys(Message carrier) {
        return carrier.headers.keySet();
    }

    @Override
    public String get(Message carrier, String key) {
        return carrier.headers.get(key);
    }

}
