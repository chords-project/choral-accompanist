package dev.chords.microservices.benchmark;

import java.io.Serializable;
import choral.channels.SymChannel;
//import choral.channels.SymChannel;
//import choral.channels.AsyncSymChannel;
//import choral.channels.AsyncSymChannel;

import java.util.ArrayList;

class ParallelChoreography4@(Start, A, B, C, D) {
    private SymChannel@(Start, A)<Serializable> ch_StartA;
    private SymChannel@(Start, B)<Serializable> ch_StartB;
    private SymChannel@(Start, C)<Serializable> ch_StartC;
    private SymChannel@(Start, D)<Serializable> ch_StartD;

    private GreeterService@A greeter_A;
    private GreeterService@B greeter_B;
    private GreeterService@C greeter_C;
    private GreeterService@D greeter_D;

    public ParallelChoreography4(
        SymChannel@(Start, A)<Serializable> ch_StartA,
        SymChannel@(Start, B)<Serializable> ch_StartB,
        SymChannel@(Start, C)<Serializable> ch_StartC,
        SymChannel@(Start, D)<Serializable> ch_StartD,
        GreeterService@A greeter_A,
        GreeterService@B greeter_B,
        GreeterService@C greeter_C,
        GreeterService@D greeter_D
    ) {
        this.ch_StartA = ch_StartA;
        this.ch_StartB = ch_StartB;
        this.ch_StartC = ch_StartC;
        this.ch_StartD = ch_StartD;

        this.greeter_A = greeter_A;
        this.greeter_B = greeter_B;
        this.greeter_C = greeter_C;
        this.greeter_D = greeter_D;
    }

    public ArrayList@Start<Long> chain() {
        // Signal to A that the chain should begin
        ch_StartA.<String>com("start"@Start);
        ch_StartB.<String>com("start"@Start);
        ch_StartC.<String>com("start"@Start);
        ch_StartD.<String>com("start"@Start);

        Long@A t1_a = System@A.nanoTime();
        greeter_A.greet("Name A"@A);
        Long@A t2_a = System@A.nanoTime();

        Long@B t1_b = System@B.nanoTime();
        greeter_B.greet("Name B"@B);
        Long@B t2_b = System@B.nanoTime();

        Long@C t1_c = System@C.nanoTime();
        greeter_C.greet("Name C"@C);
        Long@C t2_c = System@C.nanoTime();

        Long@D t1_d = System@D.nanoTime();
        greeter_D.greet("Name D"@D);
        Long@D t2_d = System@D.nanoTime();

        Long@Start latency_a = ch_StartA.<Long>com(t2_a - t1_a);
        Long@Start latency_b = ch_StartB.<Long>com(t2_b - t1_b);
        Long@Start latency_c = ch_StartC.<Long>com(t2_c - t1_c);
        Long@Start latency_d = ch_StartD.<Long>com(t2_d - t1_d);
        ArrayList@Start<Long> latencies = new ArrayList@Start<Long>();
        latencies.add(latency_a);
        latencies.add(latency_b);
        latencies.add(latency_c);
        latencies.add(latency_d);
        return latencies;
    }
}
