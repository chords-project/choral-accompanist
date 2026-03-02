package choral.channels;

import choral.lang.Unit;
import choral.channels.DiChannel_A;

public interface AsyncDiChannel_A< T > extends DiChannel_A<T> {
	< S extends T > Unit fcom( S m );
}
