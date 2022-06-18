package rpm.format.rpm;

import ctrmap.stdlib.io.base.iface.DataOutputEx;
import ctrmap.stdlib.io.structs.StringTable;
import ctrmap.stdlib.math.BitMath;
import ctrmap.stdlib.util.ArraysEx;
import java.io.IOException;
import java.util.List;

/**
 * A code symbol, RPM flavour.
 *
 * struct RPMSymbol { //sizeof 12 
 * RPM_NAMEOFS Name; 
 * uint16_t Size; 
 * RPMAddress Addr; 
 * RPMSymbolType Type; (uint8_t) 
 * uint8_t Attr; 
 * uint16_t Reserved16; 
 * }
 */
public class RPMSymbol {

	public static final int BYTES = 12;

	public static final int RPM_SYMATTR_EXPORT = 1 << 0;
	public static final int RPM_SYMATTR_IMPORT = 1 << 1;

	public String name;
	public RPMSymbolType type;
	public int attributes = 0;
	public RPMSymbolAddress address;
	public int size;

	public int nameHash;

	public RPMSymbol() {

	}

	public RPMSymbol(RPM rpm, RPMSymbol source) {
		name = source.name;
		type = source.type;
		address = new RPMSymbolAddress(rpm, source.address);
		size = source.size;
	}

	public RPMSymbol(RPM rpm, String name, RPMSymbolType type, RPMSymbolAddress addr) {
		this.name = name;
		this.type = type;
		this.address = addr;
	}

	RPMSymbol(RPM rpm, RPMReader in) throws IOException {
		if (in.aligned()) {
			name = in.readStringWithAddress();
			size = in.readUnsignedShort();
			address = new RPMSymbolAddress(rpm, in);
			type = RPMSymbolType.values()[in.read()];
			attributes = in.read();
			in.readShort(); //reserved
		} else {
			if (in.versionOver(RPMRevisions.REV_SYMBSTR_TABLE)) {
				name = in.readStringWithAddress();
			} else {
				name = in.readString();
			}
			if (name == null || name.isEmpty()) {
				name = null;
			}
			int typeCfg = in.readUnsignedByte();
			type = RPMSymbolType.values()[typeCfg & 0b111];
			attributes = typeCfg >> 3;
			address = new RPMSymbolAddress(rpm, in);
			if (in.versionOver(RPMRevisions.REV_SYMBOL_LENGTH)) {
				if (in.versionOver(RPMRevisions.REV_SMALL_SYMBOLS)) {
					size = in.readUnsignedShort();
				} else {
					size = in.readInt();
				}
			}
		}
	}

	public void updateNameHash() {
		if (name != null) {
			nameHash = RPMSymbolAddress.getNameHash(name);
		}
	}

	public boolean isAttribute(int attr) {
		return (attributes & attr) != 0;
	}

	public void addAttribute(int attr) {
		attributes |= attr;
	}

	public void clearAttribute(int attr) {
		attributes &= ~attr;
	}

	public boolean isLocal() {
		return !isImportSymbol() && address.getAddrType() == RPMSymbolAddress.RPMAddrType.LOCAL;
	}

	public boolean isGlobal() {
		return !isImportSymbol() && address.getAddrType() == RPMSymbolAddress.RPMAddrType.GLOBAL;
	}

	public boolean isImportSymbol() {
		return isAttribute(RPM_SYMATTR_IMPORT);
	}

	public boolean isExportSymbol() {
		return isAttribute(RPM_SYMATTR_EXPORT);
	}

	public void setIsExportSymbol(boolean value) {
		attributes = BitMath.setIntegerBit(attributes, 0, value);
	}

	public int getByteSize() {
		//return 2 + 1 + 4 + 2;
		//nameptr + type + addr + size
		return 2 + 2 + 4 + 1 + 1 + 2;
	}

	public void addStrings(List<String> l) {
		ArraysEx.addIfNotNullOrContains(l, name);
	}

	public void write(DataOutputEx out, StringTable strtab) throws IOException {
		strtab.putStringOffset(name);
		out.writeShort(size);
		address.write(out);
		out.write(type.ordinal());
		out.write(attributes);
		out.writeShort(0);
	}

	@Override
	public String toString() {
		return name + "(" + type + ") @ 0x" + Integer.toHexString(address.getAddrAbs()) + "(" + (address.getAddrType()) + ")" + " [0x" + Integer.toHexString(size) + "]";
	}
}
