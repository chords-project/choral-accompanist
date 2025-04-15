package dev.chords.microservices.benchmark;

import java.io.Serializable;
import choral.channels.SymChannel;
//import choral.channels.SymChannel;
//import choral.channels.AsyncSymChannel;
//import choral.channels.AsyncSymChannel;

import java.util.ArrayList;

class ParallelChoreography2@(Start, A, B) {
    private SymChannel@(Start, A)<Serializable> ch_StartA;
    private SymChannel@(Start, B)<Serializable> ch_StartB;

    private GreeterService@A greeter_A;
    private GreeterService@B greeter_B;

    public ParallelChoreography2(
        SymChannel@(Start, A)<Serializable> ch_StartA,
        SymChannel@(Start, B)<Serializable> ch_StartB,
        GreeterService@A greeter_A,
        GreeterService@B greeter_B
    ) {
        this.ch_StartA = ch_StartA;
        this.ch_StartB = ch_StartB;

        this.greeter_A = greeter_A;
        this.greeter_B = greeter_B;
    }

    public ArrayList@Start<Long> chain() {
        ch_StartA.<String>com("start"@Start);
        ch_StartB.<String>com("start"@Start);

        Long@A t1_a = System@A.nanoTime();
        greeter_A.greet("Name A"@A);
        Long@A t2_a = System@A.nanoTime();

        Long@B t1_b = System@B.nanoTime();
        greeter_B.greet("Name B"@B);
        Long@B t2_b = System@B.nanoTime();

        Long@Start latency_a = ch_StartA.<Long>com(t2_a - t1_a);
        Long@Start latency_b = ch_StartB.<Long>com(t2_b - t1_b);
        ArrayList@Start<Long> latencies = new ArrayList@Start<Long>();
        latencies.add(latency_a);
        latencies.add(latency_b);
        return latencies;
    }
}
