package choral.channels;

import choral.lang.Unit;
import choral.channels.DiChannel_B;

public interface AsyncDiChannel_B< T > extends DiChannel_B<T> {
	< S extends T > Future< S > fcom(Unit m );
	< S extends T > Future< S > fcom();
}
