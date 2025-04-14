package dev.chords.microservices.benchmark;

import java.io.Serializable;
import choral.channels.DiChannel;
//import choral.channels.SymChannel;
//import choral.channels.AsyncDiChannel;
//import choral.channels.AsyncSymChannel;

import java.util.ArrayList;

class ChainChoreography2@(Start, A, B) {
    private DiChannel@(Start, A)<Serializable> ch_StartA;
    private DiChannel@(B, Start)<Serializable> ch_BStart;

    private DiChannel@(A, B)<Serializable> ch_AB;

    private GreeterService@A greeter_A;
    private GreeterService@B greeter_B;

    public ChainChoreography2(
        DiChannel@(Start, A)<Serializable> ch_StartA,
        DiChannel@(B, Start)<Serializable> ch_BStart,
        DiChannel@(A, B)<Serializable> ch_AB,
        GreeterService@A greeter_A,
        GreeterService@B greeter_B
    ) {
        this.ch_StartA = ch_StartA;
        this.ch_BStart = ch_BStart;

        this.ch_AB = ch_AB;

        this.greeter_A = greeter_A;
        this.greeter_B = greeter_B;
    }

    public ArrayList@Start<Long> chain() {
        // Signal to A that the chain should begin
        ch_StartA.<String>com("start"@Start);

        ArrayList@A<Long> latencies_a = new ArrayList@A<Long>();
        Long@A t1_a = System@A.nanoTime();
        greeter_A.greet("Name A"@A);
        Long@A t2_a = System@A.nanoTime();
        latencies_a.add(t2_a - t1_a);

        ArrayList@B<Long> latencies_b = ch_AB.<ArrayList<Long>>com(latencies_a);
        Long@B t1_b = System@B.nanoTime();
        greeter_B.greet("Name B"@B);
        Long@B t2_b = System@B.nanoTime();
        latencies_b.add(t2_b - t1_b);

        return ch_BStart.<ArrayList<Long>>com(latencies_b);
    }
}
