package rpm.format.rpm;

import xstandard.io.structs.StringTable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Relocation info for binary addresses in a RPM's code image.
 *
 * <pre> CURRENT (0.10) 
 * {@code
 * struct RPMRelocation { //sizeof 8
 *     RPMRelocationTarget Target;
 *     RPMRelocationSource Source;
 * }}</pre>
 *
 * <pre> LEGACY (0.1 through 0.9)
 * {@code
 * struct RPMRelocation { //sizeof 16
 *     RPMRelSourceType	SrcType; //uint8_t
 *     RPMRelTargetType	TgtType; //uint8_t 
 *     uint16_t	Reserved; 
 *     RPMRelocationSource? Source; //reserved 4 bytes of space.
 *     RPMRelocationTarget? Target; //reserved 8 bytes of space.
 * }}</pre>
 */
public class RPMRelocation {

	public RPMRelocationTarget target;
	public RPMRelocationSource source;

	public RPMRelocation() {

	}

	public RPMRelocation(RPM rpm, RPMRelocation rel, Map<RPMSymbol, RPMSymbol> symbolTransferMap) {
		target = new RPMRelocationTarget(rel.target);
		RPMRelocationSource src = rel.source;
		RPMSymbol newSymb = symbolTransferMap.get(src.symb);
		if (src.symb != null && src.symb != newSymb) {
			//System.out.println("Transferring internal relocation from symbol " + is.symb + " to " + newSymb);
		}
		source = new RPMRelocationSource(rpm, newSymb);
	}

	RPMRelocation(RPMReader in, RPM rpm, String[] externModuleTable) throws IOException {
		if (in.aligned()) {
			if (in.versionOver(RPMRevisions.REV_COMPACT_RELOCATIONS)) {
				target = new RPMRelocationTarget(in, externModuleTable);
				source = new RPMRelocationSource(rpm, in);
			} else {
				RPMRelSourceType_Legacy sourceType = RPMRelSourceType_Legacy.values()[in.read()];
				RPMRelTargetType targetType = RPMRelTargetType.values()[in.read()];
				in.readShort();

				switch (sourceType) {
					case SYMBOL_EXTERNAL:
						in.skipBytes(4); //NO LONGER SUPPORTED
						break;
					case SYMBOL_INTERNAL:
						source = new RPMRelocationSource(rpm, in);
						break;
				}

				target = new RPMRelocationTarget(in, null);
				target.targetType = targetType;
			}
		} else {
			int cfg = in.readUnsignedByte();
			RPMRelSourceType_Legacy sourceType = RPMRelSourceType_Legacy.values()[cfg & 0b11]; //reserved 4 values
			RPMRelTargetType targetType = RPMRelTargetType.values()[(cfg >> 2) & 0b1111]; //reserved 16 values

			target = new RPMRelocationTarget(in, null);
			target.targetType = targetType;

			switch (sourceType) {
				case SYMBOL_EXTERNAL:
					in.skipBytes(4);
					break;
				case SYMBOL_INTERNAL:
					source = new RPMRelocationSource(rpm, in);
					break;
			}
		}
	}

	void registStrings(StringTable strtbl) {
		target.registStrings(strtbl);
	}

	void write(RPMWriter out, StringTable strtbl, List<String> outExternModuleTable) throws IOException {
		/*
		Legacy structure
		out.write(sourceType.ordinal());
		out.write(targetType.ordinal());
		out.writeShort(0);
		source.write(out, strtbl);
		target.write(out, strtbl);*/
		target.write(out, strtbl, outExternModuleTable);
		source.write(out, strtbl);
	}

	/**
	 * Gets the serialized size of the relocation info.
	 *
	 * @return
	 */
	public int getSize() {
		return 8;
	}

	@Override
	public String toString() {
		return target + " -> " + source;
	}

	/**
	 * Type of the provider of the address that the relocated field points to.
	 */
	public static enum RPMRelSourceType_Legacy {
		/**
		 * A symbol inside the RPM.
		 */
		SYMBOL_INTERNAL,
		/**
		 * A symbol outside of the RPM, handled by an RPMExternalSymbolResolver.
		 */
		SYMBOL_EXTERNAL
	}
}
