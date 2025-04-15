package dev.chords.microservices.benchmark;

import java.io.Serializable;
import choral.channels.SymChannel;
//import choral.channels.SymChannel;
//import choral.channels.AsyncSymChannel;
//import choral.channels.AsyncSymChannel;

import java.util.ArrayList;

class ParallelChoreography3@(Start, A, B, C) {
    private SymChannel@(Start, A)<Serializable> ch_StartA;
    private SymChannel@(Start, B)<Serializable> ch_StartB;
    private SymChannel@(Start, C)<Serializable> ch_StartC;

    private GreeterService@A greeter_A;
    private GreeterService@B greeter_B;
    private GreeterService@C greeter_C;

    public ParallelChoreography3(
        SymChannel@(Start, A)<Serializable> ch_StartA,
        SymChannel@(Start, B)<Serializable> ch_StartB,
        SymChannel@(Start, C)<Serializable> ch_StartC,
        GreeterService@A greeter_A,
        GreeterService@B greeter_B,
        GreeterService@C greeter_C
    ) {
        this.ch_StartA = ch_StartA;
        this.ch_StartB = ch_StartB;
        this.ch_StartC = ch_StartC;

        this.greeter_A = greeter_A;
        this.greeter_B = greeter_B;
        this.greeter_C = greeter_C;
    }

    public ArrayList@Start<Long> chain() {
        // Signal to A that the chain should begin
        ch_StartA.<String>com("start"@Start);
        ch_StartB.<String>com("start"@Start);
        ch_StartC.<String>com("start"@Start);

        Long@A t1_a = System@A.nanoTime();
        greeter_A.greet("Name A"@A);
        Long@A t2_a = System@A.nanoTime();

        Long@B t1_b = System@B.nanoTime();
        greeter_B.greet("Name B"@B);
        Long@B t2_b = System@B.nanoTime();

        Long@C t1_c = System@C.nanoTime();
        greeter_C.greet("Name C"@C);
        Long@C t2_c = System@C.nanoTime();

        Long@Start latency_a = ch_StartA.<Long>com(t2_a - t1_a);
        Long@Start latency_b = ch_StartB.<Long>com(t2_b - t1_b);
        Long@Start latency_c = ch_StartC.<Long>com(t2_c - t1_c);
        ArrayList@Start<Long> latencies = new ArrayList@Start<Long>();
        latencies.add(latency_a);
        latencies.add(latency_b);
        latencies.add(latency_c);
        return latencies;
    }
}
