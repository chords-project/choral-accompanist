package choral.reactive.tracing;

import choral.reactive.connection.Message;
import io.opentelemetry.context.propagation.TextMapSetter;

public class HeaderTextMapSetter implements TextMapSetter<Message> {

    @SuppressWarnings("null")
    @Override
    public void set(Message carrier, String key, String value) {
        carrier.headers.put(key, value);
    }

}
