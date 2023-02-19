package rpm.elfconv;

import xstandard.arm.elf.format.ELFSectionHeader;

/**
 *
 */
public enum SectionType {
	TEXT,
	BSS,
	INIT_ARRAY,
	FINI_ARRAY,
	EXTERN;

	public static SectionType getSectionTypeFromElf(ELFSectionHeader hdr) {
		if (hdr.name == null) {
			return EXTERN;
		}
		if ((hdr.flags & ELFSectionHeader.SHF_ALLOC) != 0) {
			switch (hdr.type.getSectionType()) {
				case NOBITS:
					return BSS;
				case PROGBITS:
					return TEXT;
				case INIT_ARRAY:
					return INIT_ARRAY;
				case FINI_ARRAY:
					return FINI_ARRAY;
			}
		}
		return null;
	}
}
