// Hack until String becomes serializable in choral std lib

package dev.chords.choreographies;

import java.io.Serializable;

class SerializableString@A implements Serializable@A {
    public final String@A string;

    public SerializableString(String@A string) {
        this.string = string;
    }

    public String@A toString() {
        return "\""@A + string + "\""@A;
    }
}