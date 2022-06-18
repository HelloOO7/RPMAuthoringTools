
package rpm.format.rpm;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class RPMSymbolAddress {
	
	private int bits;
	private final RPM rpm;
	
	public RPMSymbolAddress(RPM rpm, DataInput in) throws IOException{
		this.rpm = rpm;
		bits = in.readInt();
	}
	
	public RPMSymbolAddress(RPM rpm, RPMAddrType t, int addr){
		this.rpm = rpm;
		setAddr(addr);
		setAddrType(t);
	}
	
	public RPMSymbolAddress(RPM rpm, String importSymbolName) {
		this.rpm = rpm;
		bits = getNameHash(importSymbolName);
	}
	
	public RPMSymbolAddress(RPM rpm, RPMSymbolAddress addr){
		this.rpm = rpm;
		this.bits = addr.bits;
	}
	
	public static int getNameHash(String name) {
		if (name == null) {
			return 0;
		}
		//FNV1a-32
		int hash = 0x811C9DC5;
		int len = name.length();
		for (int i = 0; i < len; i++) {
			hash = (hash ^ name.charAt(i)) * 16777619;
		}
		return hash;
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
