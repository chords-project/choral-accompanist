package dev.chords.travel.choreographies;

import java.util.ArrayList;

public interface SearchService@A {
    // Returns a list of hotel ids
    SerializableList@A<String> nearby(Double@A lat, Double@A lon, String@A inDate, String@A outDate);
}