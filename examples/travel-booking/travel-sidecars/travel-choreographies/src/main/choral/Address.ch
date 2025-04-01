package dev.chords.travel.choreographies;

public class Address@A {
    public final String@A streetNumber;
    public final String@A streetName;
    public final String@A city;
    public final String@A country;
    public final String@A postalCode;
    public final Double@A lat;
    public final Double@A lon;

    public Address(
        String@A streetNumber,
        String@A streetName,
        String@A city,
        String@A country,
        String@A postalCode,
        Double@A lat,
        Double@A lon
    ) {
        this.streetNumber = streetNumber;
        this.streetName = streetName;
        this.city = city;
        this.country = country;
        this.postalCode = postalCode;
        this.lat = lat;
        this.lon = lon;
    }
}