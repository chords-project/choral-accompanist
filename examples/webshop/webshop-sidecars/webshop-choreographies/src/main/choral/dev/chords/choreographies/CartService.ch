package dev.chords.choreographies;

public interface CartService@A {
    void addItem(String@A userID, String@A productID, int@A quantity);
    Cart@A getCart(String@A userID);
    void emptyCart(String@A userID);
}