package dev.chords.microservices.benchmark;

import java.io.Serializable;
//import choral.channels.DiChannel;
import choral.channels.SymChannel;
//import choral.channels.AsyncDiChannel;
//import choral.channels.AsyncSymChannel;

import java.util.ArrayList;

public class ChainChoreography1@(Start, A) {
    private SymChannel@(Start, A)<Serializable> ch_StartA;

    private GreeterService@A greeter_A;

    public ChainChoreography1(
        SymChannel@(Start, A)<Serializable> ch_StartA,
        GreeterService@A greeter_A
    ) {
        this.ch_StartA = ch_StartA;
        this.greeter_A = greeter_A;
    }

    public ArrayList@Start<Long> chain() {
        // Signal to A that the chain should begin
        ch_StartA.<String>com("start"@Start);

        ArrayList@A<Long> latencies_a = new ArrayList@A<Long>();
        Long@A t1_a = System@A.nanoTime();
        greeter_A.greet("Name A"@A);
        Long@A t2_a = System@A.nanoTime();
        latencies_a.add(t2_a - t1_a);

        return ch_StartA.<ArrayList<Long>>com(latencies_a);
    }
}
