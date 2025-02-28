package dev.chords.microservices.benchmark;

import java.io.Serializable;
//import choral.channels.DiChannel;
//import choral.channels.SymChannel;
import choral.channels.AsyncDiChannel;
import choral.channels.AsyncSymChannel;

class SimpleChoreography@(A, B) {
    private AsyncSymChannel@(A, B)<Serializable> ch;

    public SimpleChoreography(AsyncSymChannel@(A, B)<Serializable> ch) {
        this.ch = ch;
    }

    public void pingPong() {
        System@A.out.println("Sending ping to B..."@A);

        String@B ping = ch.<SerializableString>fcom(new SerializableString@A("Ping"@A)).get().string;
        System@B.out.println("Received "@B + ping + " from A, sending back pong..."@B);

        String@A pong = ch.<SerializableString>fcom(new SerializableString@B("Pong"@B)).get().string;
        System@A.out.println("Received "@A + pong + " from B"@A);
    }
}
