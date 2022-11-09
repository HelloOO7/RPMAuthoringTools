
package rpm.format.rpm;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class RPMSymbolAddressCompat {
	
	private int bits;
	private final RPM rpm;
	
	public RPMSymbolAddressCompat(RPM rpm, DataInput in) throws IOException{
		this.rpm = rpm;
		bits = in.readInt();
	}
	
	public void write(DataOutput out) throws IOException {
		out.writeInt(bits);
	}
	
	public int getNameHash() {
		return bits;
	}
	
	public int getAddr(){
		return (bits & 0x7FFFFFFF) << 1 >> 1;
	}

	public RPMAddrType getAddrType(){
		return RPMAddrType.values()[(bits >> 31) & 1];
	}
	
	public int getAddrAbs(){
		if (getAddrType() == RPMAddrType.GLOBAL){
			return getAddr();
		}
		else {
			return rpm.getCodeSegmentBase() + getAddr();
		}
	}
	
	public void setAddr(int addr){
		bits &= ~0x7FFFFFFF;
		bits |= addr & 0x7FFFFFFF;
	}
	
	public void setAddrType(RPMAddrType t){
		bits &= 0x7FFFFFFF;
		bits |= (t.ordinal() << 31);
	}
	
	@Override
	public String toString() {
		return "0x" + Integer.toHexString(getAddr()) + " (abs. 0x" + Integer.toHexString(getAddrAbs()) + ")";
	}
	
	public static enum RPMAddrType {
		/**
		 * Fixed, non-relocatable address.
		 */
		GLOBAL,
		/**
		 * Address pointing inside this RPM, relocatable.
		 */
		LOCAL
	}
}
