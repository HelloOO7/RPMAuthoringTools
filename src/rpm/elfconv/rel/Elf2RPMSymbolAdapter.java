package rpm.elfconv.rel;

import ctrmap.stdlib.arm.elf.format.sections.ELFSymbolSection;
import rpm.format.rpm.RPMSymbol;
import rpm.format.rpm.RPMSymbolType;

/**
 *
 */
public class Elf2RPMSymbolAdapter extends RPMSymbol {

	public ELFSymbolSection.ELFSymbol origin;

	public Elf2RPMSymbolAdapter(ELFSymbolSection.ELFSymbol origin) {
		this.origin = origin;
		name = origin.name;
		size = origin.size;
		type = getRpmSymType(origin.getSymType());
		if (origin.getVisibility() == ELFSymbolSection.ELFSymbolVisibility.DEFAULT && origin.sectionIndex != 0) { //nonextern default visibility symbol
			attributes |= RPM_SYMATTR_EXPORT;
		}
	}

	public static RPMSymbolType getRpmSymType(ELFSymbolSection.ELFSymbolType symType) {
		switch (symType) {
			case FUNC:
				return RPMSymbolType.FUNCTION_ARM;
			case NOTYPE:
			case OBJECT:
				return RPMSymbolType.VALUE;
			case SECTION:
				return RPMSymbolType.SECTION;
		}
		return null;
	}

	public static boolean acceptsSymType(ELFSymbolSection.ELFSymbolType symType) {
		switch (symType) {
			case FUNC:
			case NOTYPE:
			case OBJECT:
//			case ElfSymbol.STT_SECTION:
				return true;
		}
		return false;
	}
}
