package dev.chords.choreographies;

import java.io.Serializable;
import java.util.List;

public class Address@A implements Serializable@A {
    public String@A street_address;
    public String@A city;
    public String@A state;
    public String@A country;
    public Integer@A zip_code;

    public Address() {
        street_address = null@A;
        city = null@A;
        state = null@A;
        country = null@A;
        zip_code = 0@A;
    }

    public Address(
        String@A street_address,
        String@A city,
        String@A state,
        String@A country,
        Integer@A zip_code
    ) {
        this.street_address = street_address;
        this.city = city;
        this.state = state;
        this.country = country;
        this.zip_code = zip_code;
    }

    public String@A toString() {
        return "[ Address street="@A+street_address+" city="@A+city+" state="@A+state+" country="@A+country+" zip_code="@A+zip_code+" ]"@A;
    }
}