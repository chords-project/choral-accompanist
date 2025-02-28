package dev.chords.choreographies;

import java.io.Serializable;

public class ReqAddItem@A implements Serializable@A {
    public final String@A userID;
    public final String@A productID;
    public final int@A quantity;

    public ReqAddItem(String@A userID, String@A productID, int@A quantity) {
        this.userID = userID;
        this.productID = productID;
        this.quantity = quantity;
    }
}