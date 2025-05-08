package choral.reactive;

public class ReactiveSymChannel_A<M> extends ReactiveSymChannel<M> {
    public ReactiveSymChannel_A(ReactiveChannel_A<M> chanA, ReactiveChannel_B<M> chanB) {
        super(chanA, chanB);
    }
}
