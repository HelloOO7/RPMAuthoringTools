package rpm.format.rpm;

import xstandard.io.structs.StringTable;
import java.io.DataOutput;
import java.io.IOException;

public class RPMRelocationSource {

	public RPMSymbol symb;
	protected RPM rpm;

	protected RPMRelocationSource() {

	}

	public RPMRelocationSource(RPM rpm, RPMSymbol symb) {
		this.symb = symb;
		this.rpm = rpm;
	}

	RPMRelocationSource(RPM rpm, RPMReader in) throws IOException {
		int symId = in.readUnsignedShort();
		symb = rpm.getSymbol(symId);
		if (symb == null) {
			throw new RuntimeException("Could not find symbol by ID " + symId + "!");
		}
		this.rpm = rpm;
		if (in.aligned()) {
			if (!in.versionOver(RPMRevisions.REV_COMPACT_RELOCATIONS)) {
				in.readShort();
			}
		}
	}

	public int getAbsoluteAddress() {
		if (symb == null) {
			throw new NullPointerException("Null symbol!");
		}
		if (symb.isImportSymbol()) {
			return -1;
		}
		return symb.getAddrAbs();
	}

	public void write(DataOutput out, StringTable strtab) throws IOException {
		out.writeShort(rpm.getSymbolNo(symb));
	}

	public int getAddress() {
		return symb.isImportSymbol() ? -1 : symb.address;
	}

	public int getWritableAddress() {
		int a = getAbsoluteAddress();
		if (a == -1) {
			return a;
		}
		if (symb.type == RPMSymbolType.FUNCTION_THM) {
			a++;
		}
		return a;
	}

	public int getLength() {
		return symb.size;
	}

	public int getDataSize() {
		return 2;
	}
	
	@Override
	public String toString() {
		return symb.toString();
	}
}
