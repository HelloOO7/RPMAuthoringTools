package rpm.format.rpm;

import xstandard.io.base.impl.ext.data.DataIOStream;
import xstandard.io.structs.StringTable;
import xstandard.util.ArraysEx;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class RPMRelocationTarget {

	public static final String MODULE_BASE = "base";

	public String module = MODULE_BASE;
	public int address;
	public RPMRelTargetType targetType;

	RPMRelocationTarget(RPMReader in, String[] externModuleTable) throws IOException {
		address = in.readInt();
		if (!in.versionOver(RPMRevisions.REV_COMPACT_RELOCATIONS)) {
			boolean hasModule = ((address >> 31) & 1) != 0;
			address &= 0x7FFFFFFF;

			if (hasModule) {
				module = in.readStringWithAddress();
				if (in.aligned()) {
					in.readShort();
				}
			} else if (in.aligned()) {
				in.readInt();
			}
		}
		else {
			int externModuleIndex = in.read();
			if (externModuleIndex != 0xFF) {
				module = externModuleTable[externModuleIndex];
			}
			targetType = RPMRelTargetType.values()[in.read()];
		}
	}

	public RPMRelocationTarget(int address, RPMRelTargetType type) {
		this.address = address;
		this.targetType = type;
	}

	public RPMRelocationTarget(int address, String module, RPMRelTargetType type) {
		this(address, type);
		this.module = module;
	}

	public RPMRelocationTarget(RPMRelocationTarget tgt) {
		address = tgt.address;
		module = tgt.module;
		targetType = tgt.targetType;
	}

	public void addStrings(List<String> l) {
		if (!isInternal()) {
			ArraysEx.addIfNotNullOrContains(l, module);
		}
	}

	public int getAddrHWordAligned() {
		return address & 0xFFFFFFFE;
	}

	public int getSize() {
		/*if (isInternal()) {
			return 4;
		} else {
			return 6;
		}*/
		return 6; //packed struct
	}

	public boolean isInternal() {
		return Objects.equals(module, MODULE_BASE);
	}

	public boolean isExternal() {
		return !isInternal();
	}

	void registStrings(StringTable strtbl) {
		if (isExternal()) {
			strtbl.putString(module);
		}
	}

	public void write(DataIOStream out, StringTable strtbl, List<String> outExternModuleTable) throws IOException {
		/*if (isInternal()) {
			out.writeInt(address);
			out.writeInt(0);
		} else {
			out.writeInt(address | (1 << 31));
			strtbl.putStringOffset(module);
			out.writeShort(0);
		}*/
		out.writeInt(address);
		int externIdx = -1;
		if (isExternal()) {
			externIdx = outExternModuleTable.indexOf(module);
			if (externIdx == -1) {
				externIdx = outExternModuleTable.size();
				outExternModuleTable.add(module);
			}
		}
		out.write(externIdx);
		out.write(targetType.ordinal());
	}
	
	@Override
	public String toString() {
		return targetType + "@" + this.module + ": 0x" + Integer.toHexString(address);
	}
}
