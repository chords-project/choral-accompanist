package dev.chords.microservices.benchmark;

import java.io.Serializable;
import choral.channels.DiChannel;
//import choral.channels.SymChannel;
//import choral.channels.AsyncDiChannel;
//import choral.channels.AsyncSymChannel;

import java.util.ArrayList;

class ChainChoreography4@(Start, A, B, C, D) {
    private DiChannel@(Start, A)<Serializable> ch_StartA;
    private DiChannel@(D, Start)<Serializable> ch_DStart;

    private DiChannel@(A, B)<Serializable> ch_AB;
    private DiChannel@(B, C)<Serializable> ch_BC;
    private DiChannel@(C, D)<Serializable> ch_CD;

    private GreeterService@A greeter_A;
    private GreeterService@B greeter_B;
    private GreeterService@C greeter_C;
    private GreeterService@D greeter_D;

    public ChainChoreography4(
        DiChannel@(Start, A)<Serializable> ch_StartA,
        DiChannel@(D, Start)<Serializable> ch_DStart,
        DiChannel@(A, B)<Serializable> ch_AB,
        DiChannel@(B, C)<Serializable> ch_BC,
        DiChannel@(C, D)<Serializable> ch_CD,
        GreeterService@A greeter_A,
        GreeterService@B greeter_B,
        GreeterService@C greeter_C,
        GreeterService@D greeter_D
    ) {
        this.ch_StartA = ch_StartA;
        this.ch_DStart = ch_DStart;

        this.ch_AB = ch_AB;
        this.ch_BC = ch_BC;
        this.ch_CD = ch_CD;

        this.greeter_A = greeter_A;
        this.greeter_B = greeter_B;
        this.greeter_C = greeter_C;
        this.greeter_D = greeter_D;
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

        ArrayList@C<Long> latencies_c = ch_BC.<ArrayList<Long>>com(latencies_b);
        Long@C t1_c = System@C.nanoTime();
        greeter_C.greet("Name C"@C);
        Long@C t2_c = System@C.nanoTime();
        latencies_c.add(t2_c - t1_c);

        ArrayList@D<Long> latencies_d = ch_CD.<ArrayList<Long>>com(latencies_c);
        Long@D t1_d = System@D.nanoTime();
        greeter_D.greet("Name D"@D);
        Long@D t2_d = System@D.nanoTime();
        latencies_d.add(t2_d - t1_d);

        return ch_DStart.<ArrayList<Long>>com(latencies_d);
    }
}
