package dev.chords.travel.choreographies;

import java.io.Serializable;
import java.util.ArrayList;

public class SerializableList@A<T@X> implements Serializable@A {
    public final ArrayList@A<T> list;

    public SerializableList(
        ArrayList@A<T> list
    ) {
        this.list = list;
    }
}