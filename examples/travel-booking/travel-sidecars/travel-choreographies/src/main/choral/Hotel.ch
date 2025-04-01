package dev.chords.travel.choreographies;

import java.io.Serializable;
import java.util.ArrayList;

public class Hotel@A implements Serializable@A {
    public final String@A id;
    public final String@A name;
    public final String@A phoneNumber;
    public final String@A description;
    public final Address@A address;
    public final ArrayList@A<Image> images;

    public Hotel(
        String@A id,
        String@A name,
        String@A phoneNumber,
        String@A description,
        Address@A address,
        ArrayList@A<Image> images
    ) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.description = description;
        this.address = address;
        this.images = images;
    }
}