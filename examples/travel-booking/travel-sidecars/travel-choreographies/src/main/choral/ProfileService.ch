package dev.chords.travel.choreographies;

import java.util.ArrayList;

public interface ProfileService@A {
    SerializableList@A<Hotel> getProfiles(SerializableList@A<String> hotelID, String@A locale);
}