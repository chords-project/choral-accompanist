package dev.chords.microservices.benchmark;

import java.io.Serializable;
import choral.channels.DiChannel;
//import choral.channels.SymChannel;
//import choral.channels.AsyncDiChannel;
//import choral.channels.AsyncSymChannel;

class ChainChoreography@(A, B) {
    private DiChannel@(A, B)<Serializable> ch;
    private GreeterService@B greeter;

    public ChainChoreography(DiChannel@(A, B)<Serializable> ch, GreeterService@B greeter) {
        this.ch = ch;
        this.greeter = greeter;
    }

    public String@B forward(String@A value) {
        String@B value_b = ch.<SerializableString>com(new SerializableString@A(value)).string;
        String@B greet_value_b = greeter.greet(value_b);
        return greet_value_b;
    }
}
