package choral.reactive.connection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;

import com.google.protobuf.ByteString;

import choral.reactive.Session;
import choral_reactive.ChannelOuterClass;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;

public class Message implements Serializable {

    public final Session session;
    public final Serializable message;
    public final int sequenceNumber;

    public final HashMap<String, String> headers;
    public SerializedSpanContext senderSpanContext;

    public Message(Session session, Serializable message, int sequenceNumber) {
        this.session = session;
        this.message = message;
        this.sequenceNumber = sequenceNumber;
        this.headers = new HashMap<>();
    }

    public static class SerializedSpanContext implements Serializable {

        String traceIdHex;
        String spanIdHex;
        Byte traceFlags;
        HashMap<String, String> traceState;

        public SerializedSpanContext(SpanContext spanContext) {
            this.traceIdHex = spanContext.getTraceId();
            this.spanIdHex = spanContext.getSpanId();
            this.traceFlags = spanContext.getTraceFlags().asByte();
            this.traceState = new HashMap<>(spanContext.getTraceState().asMap());
        }

        public SerializedSpanContext(ChannelOuterClass.SpanContext grpcSpan) {
            this.traceIdHex = grpcSpan.getTraceIdHex();
            this.spanIdHex = grpcSpan.getSpanIdHex();
            this.traceFlags = grpcSpan.getTraceFlags().byteAt(0);
            this.traceState = new HashMap<>(grpcSpan.getTraceStateMap());
        }

        public SpanContext toSpanContext() {
            TraceStateBuilder stateBuilder = TraceState.builder();
            traceState.forEach((key, value) -> stateBuilder.put(key, value));
            return SpanContext.createFromRemoteParent(traceIdHex, spanIdHex, TraceFlags.fromByte(traceFlags),
                    stateBuilder.build());
        }
    }

    @Override
    public String toString() {
        return "Message [ session=" + session + " message=" + message + "]";
    }

    public byte[] serialize() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(buf)) {
            oos.writeObject(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return buf.toByteArray();
    }

    public static Message deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream buf = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(buf);
        return (Message) ois.readObject();
    }

    public Message(ChannelOuterClass.Message grpcMessage) throws Exception {
        this.session = new Session(grpcMessage.getChoreography(), grpcMessage.getSender(), grpcMessage.getSessionId());
        this.headers = new HashMap<>(grpcMessage.getHeadersMap());
        this.sequenceNumber = grpcMessage.getSequenceNumber();

        ByteArrayInputStream stream = new ByteArrayInputStream(grpcMessage.getPayload().toByteArray());
        ObjectInputStream ois = new ObjectInputStream(stream);
        this.message = (Serializable) ois.readObject();

        this.senderSpanContext = new SerializedSpanContext(grpcMessage.getSpanContext());
    }

    public ChannelOuterClass.Message toGrpcMessage() {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(buf)) {
            oos.writeObject(message);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return ChannelOuterClass.Message.newBuilder()
                .setChoreography(this.session.choreographyName())
                .setSender(this.session.senderName())
                .setSequenceNumber(this.sequenceNumber)
                .setSessionId(this.session.sessionID())
                .setPayload(ByteString.copyFrom(buf.toByteArray()))
                .putAllHeaders(this.headers)
                .setSpanContext(ChannelOuterClass.SpanContext.newBuilder()
                        .setTraceIdHex(this.senderSpanContext.traceIdHex)
                        .setSpanIdHex(this.senderSpanContext.spanIdHex)
                        .setTraceFlags(ByteString.copyFrom(new byte[]{this.senderSpanContext.traceFlags}))
                        .putAllTraceState(this.senderSpanContext.traceState))
                .build();
    }
}
