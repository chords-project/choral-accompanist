package org.choral.accompanist.channels;

public interface Future<T> {
    T get();
}
