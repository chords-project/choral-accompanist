package dev.chords.microservices.benchmark;

import java.io.Serializable;
//import choral.channels.DiChannel;
//import choral.channels.SymChannel;
import choral.channels.AsyncDiChannel;
import choral.channels.AsyncSymChannel;

public class SimpleChoreography@(A, B) {
    private AsyncSymChannel@(A, B)<Serializable> ch;

    public SimpleChoreography(AsyncSymChannel@(A, B)<Serializable> ch) {
        this.ch = ch;
    }

    public void pingPong() {
        System@A.out.println("Sending ping to B..."@A);

        String@B ping = ch.<String>fcom("Ping"@A).get();
        System@B.out.println("Received "@B + ping + " from A, sending back pong..."@B);

        String@A pong = ch.<String>fcom("Pong"@B).get();
        System@A.out.println("Received "@A + pong + " from B"@A);
    }
}
