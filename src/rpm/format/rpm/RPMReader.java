package rpm.format.rpm;

import xstandard.io.base.iface.IOStream;
import xstandard.io.base.impl.ext.data.DataIOStream;
import java.io.IOException;

class RPMReader extends DataIOStream {
	
	private int version;
	
	public RPMReader(IOStream strm){
		super(strm);
	}
	
	public void setVersion(int version) {
		this.version = version;
	}
	
	public boolean aligned() {
		return versionOver(RPMRevisions.REV_ALIGNED_FORMAT);
	}
	
	public boolean versionOver(int rev) {
		return version >= rev;
	}
	
	private int headerOffsetBase = 0;
	
	public void setHeaderOffsetBase(int value) {
		this.headerOffsetBase = value;
	}
	
	public int readHeaderOffset() throws IOException {
		int offs = readInt();
		if (offs == -1) {
			return offs;
		}
		return offs + headerOffsetBase;
	}
	
	private int strTableOffs = -1;
	
	public void setStrTableOffsHere() throws IOException {
		strTableOffs = getPosition();
	}
	
	@Override
	public String readStringWithAddress() throws IOException {
		if (strTableOffs == -1){
			return super.readStringWithAddress();
		}
		int addr = readUnsignedShort();
		if (addr == 0){
			return null;
		}
		addr += strTableOffs;
		int pos = getPositionUnbased();
		seek(addr);
		String str = readString();
		seekUnbased(pos);
		return str;
	}
}
