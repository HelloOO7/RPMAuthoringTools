package rpm.format.rpm;

import xstandard.io.base.impl.ext.data.DataIOStream;
import java.io.IOException;
import xstandard.io.structs.TemporaryOffset;

class RPMWriter extends DataIOStream {
	
	private boolean align = true;
	private int headerStartOffset = 0;
	
	public RPMWriter(){
		super();
	}
	
	public void setHeaderStartHere() throws IOException {
		this.headerStartOffset = getPosition();
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
	
	public TemporaryOffset createTempOffset() throws IOException {
		return new HeaderRelativeOffset();
	}
	
	private class HeaderRelativeOffset extends TemporaryOffset {
		
		public HeaderRelativeOffset() throws IOException {
			super(RPMWriter.this, -headerStartOffset);
		}
		
	}
}
