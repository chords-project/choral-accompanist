package choral.reactive;

import choral.channels.*;
import choral.lang.Unit;

public class ReactiveSymChannel<M> implements AsyncSymChannel_A<M>, AsyncSymChannel_B<M> {

    private final ReactiveChannel_A<M> chanA;
    private final ReactiveChannel_B<M> chanB;

    public ReactiveSymChannel(ReactiveChannel_A<M> chanA, ReactiveChannel_B<M> chanB) {
        this.chanA = chanA;
        this.chanB = chanB;
    }

    @Override
    public <T extends M> Unit fcom(T msg) {
        return chanA.fcom(msg);
    }

    @Override
    public <T extends M> Future<T> fcom() {
        return chanB.fcom();
    }

    @Override
    public <T extends M> Future<T> fcom(Unit unit) {
        return fcom();
    }

    @Override
    public <S extends M> Unit com(S s) {
        return fcom(s);
    }

    @Override
    public <S extends M> S com(Unit unit) {
        return com();
    }

    @Override
    public <S extends M> S com() {
        return this.<S>fcom().get();
    }

    @Override
    public <T extends Enum<T>> Unit select(T t) {
        return chanA.select(t);
    }

    @Override
    public <T extends Enum<T>> T select(Unit unit) {
        return select();
    }

    @Override
    public <T extends Enum<T>> T select() {
        return chanB.select();
    }
}
