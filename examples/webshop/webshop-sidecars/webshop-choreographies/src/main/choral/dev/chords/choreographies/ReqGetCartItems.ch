package dev.chords.choreographies;

import java.io.Serializable;

public class ReqGetCartItems@A implements Serializable@A {
    public final String@A userID;

    public ReqGetCartItems(String@A userID) {
        this.userID = userID;
    }
}