package rpm.format.rpm;

import ctrmap.stdlib.io.base.impl.ext.data.DataIOStream;
import java.io.IOException;

class RPMWriter extends DataIOStream {
	
	private boolean align = true;
	
	public RPMWriter(){
		super();
	}
	
	public void setAlignEnable(boolean val) {
		align = val;
	}
	
	public boolean isAlignEnable() {
		return align;
	}
	
	public void pad16() throws IOException {
		if (align) {
			pad(2);
		}
	}
	
	public void pad32() throws IOException {
		if (align) {
			pad(4);
		}
	}
}
