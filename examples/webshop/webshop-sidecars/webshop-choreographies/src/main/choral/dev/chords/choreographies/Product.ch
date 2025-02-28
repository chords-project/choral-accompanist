package dev.chords.choreographies;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;

public class Product@A implements Serializable@A {
    public String@A id;
    public String@A name;
    public String@A description;
    public String@A picture;
    public Money@A priceUSD;
    public List@A<String> categories;

    public Product() {
        id = null@A;
        name = null@A;
        description = null@A;
        picture = null@A;
        priceUSD = new Money@A();
        categories = new ArrayList@A<String>();
    }

    public Product(
        String@A id,
        String@A name,
        String@A description,
        String@A picture,
        Money@A priceUSD,
        List@A<String> categories
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.picture = picture;
        this.priceUSD = priceUSD;
        this.categories = categories;
    }

    public String@A toString() {
        return "[ Product id="@A+id+" name="@A+name+" ]"@A;
    }
}