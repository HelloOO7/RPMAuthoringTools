package rpm.format.rpm;

import xstandard.io.base.iface.DataOutputEx;
import xstandard.io.structs.StringTable;
import xstandard.math.BitMath;
import xstandard.util.ArraysEx;
import java.io.IOException;
import java.util.List;

/**
 * A code symbol, RPM flavour.
 *
 * struct RPMSymbol { //sizeof 12 RPM_NAMEOFS Name; uint16_t Size; RPMAddress Addr; RPMSymbolType Type;
 * (uint8_t) uint8_t Attr; uint16_t Reserved16; }
 */
public class RPMSymbol {

	public static final int BYTES = 12;

	public static final int RPM_SYMATTR_EXPORT = 1 << 0;
	public static final int RPM_SYMATTR_IMPORT = 1 << 1;
	public static final int RPM_SYMATTR_GLOBAL = 1 << 2;

	private final RPM rpm;

	public String name;
	public RPMSymbolType type;
	public int attributes = 0;
	public int address;
	public int size;

	public int nameHash;

	public RPMSymbol(RPM rpm) {
		this.rpm = rpm;
	}

	public RPMSymbol(RPM rpm, RPMSymbol source) {
		this.rpm = rpm;
		name = source.name;
		type = source.type;
		address = source.address;
		size = source.size;
		attributes = source.attributes;
	}

	public RPMSymbol(RPM rpm, String name, RPMSymbolType type, int addr, boolean global) {
		this.rpm = rpm;
		this.name = name;
		this.type = type;
		this.address = addr;
		if (global) {
			addAttribute(RPM_SYMATTR_GLOBAL);
		}
	}

	RPMSymbol(RPM rpm, RPMReader in) throws IOException {
		this.rpm = rpm;
		RPMSymbolAddressCompat addrCompat = null;
		if (in.aligned()) {
			name = in.readStringWithAddress();
			size = in.readUnsignedShort();
			if (in.versionOver(RPMRevisions.REV_GLOBAL_IN_SYMATTR)) {
				address = in.readInt();
			} else {
				addrCompat = new RPMSymbolAddressCompat(rpm, in);
			}
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
			addrCompat = new RPMSymbolAddressCompat(rpm, in);
			if (in.versionOver(RPMRevisions.REV_SYMBOL_LENGTH)) {
				if (in.versionOver(RPMRevisions.REV_SMALL_SYMBOLS)) {
					size = in.readUnsignedShort();
				} else {
					size = in.readInt();
				}
			}
		}
		if (addrCompat != null) {
			if (isImportSymbol()) {
				address = addrCompat.getNameHash();
			}
			else {
				setAddress(addrCompat.getAddr(), addrCompat.getAddrType() == RPMSymbolAddressCompat.RPMAddrType.GLOBAL);
			}
		}
	}

	public int getAddrAbs() {
		if (isAttribute(RPM_SYMATTR_GLOBAL)) {
			return address;
		} else {
			return rpm.getCodeSegmentBase() + address;
		}
	}

	public void setAddress(int address, boolean global) {
		this.address = address;
		if (global) {
			addAttribute(RPM_SYMATTR_GLOBAL);
		} else {
			clearAttribute(RPM_SYMATTR_GLOBAL);
		}
	}

	public void setAddressImportHash(String name) {
		address = name == null ? 0 : getNameHash(name);
	}

	public void updateNameHash() {
		if (name != null) {
			nameHash = getNameHash(name);
		}
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
		return !isImportSymbol() && !isAttribute(RPM_SYMATTR_GLOBAL);
	}

	public boolean isGlobal() {
		return !isImportSymbol() && isAttribute(RPM_SYMATTR_GLOBAL);
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
		out.writeInt(address);
		out.write(type.ordinal());
		out.write(attributes);
		out.writeShort(0);
	}

	@Override
	public String toString() {
		return name + "(" + type + ") @ 0x" + Integer.toHexString(address) + " [0x" + Integer.toHexString(size) + "]";
	}
}
