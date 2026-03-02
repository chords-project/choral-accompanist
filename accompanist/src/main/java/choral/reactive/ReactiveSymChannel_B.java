package choral.reactive;

public class ReactiveSymChannel_B<M> extends ReactiveSymChannel<M> {
    public ReactiveSymChannel_B(ReactiveChannel_B<M> chanB, ReactiveChannel_A<M> chanA) {
        super(chanA, chanB);
    }
}
