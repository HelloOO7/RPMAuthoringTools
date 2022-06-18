package rpm.elfconv.rel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import rpm.elfconv.ExternalSymbolDB;
import ctrmap.stdlib.arm.elf.SectionType;
import ctrmap.stdlib.arm.elf.format.ELF;
import ctrmap.stdlib.arm.elf.format.sections.ELFSection;
import ctrmap.stdlib.arm.elf.format.sections.ELFSymbolSection;
import rpm.format.rpm.RPM;
import rpm.format.rpm.RPMSymbolAddress;
import rpm.format.rpm.RPMSymbolType;
import ctrmap.stdlib.io.base.impl.ext.data.DataIOStream;

/**
 *
 */
public class RelElfSection {

	public final int id;

	public final SectionType type;

	private ELFSection sec;
	private ELFSymbolSection sym;

	public final List<Elf2RPMSymbolAdapter> rpmSymbols = new ArrayList<>();

	public final int sourceOffset;
	public final int length;
	public int targetOffset;

	private DataIOStream buf;

	public RelElfSection(ELF elf, ELFSection sec, ELFSymbolSection symbols, DataIOStream io) throws IOException {
		id = elf.getSectionIndex(sec);
		sym = symbols;
		type = SectionType.getSectionTypeFromElf(sec.header);
		this.sec = sec;
		sourceOffset = sec.header.offset;
		length = sec.header.size;
		byte[] b = new byte[length];
		if (type != SectionType.BSS) {
			io.seek(sourceOffset);
			io.read(b);
		}
		buf = new DataIOStream(b);
	}

	public byte[] getBytes() {
		return buf.toByteArray();
	}

	public void prepareForRPM(RPM rpm, int targetOffset, ExternalSymbolDB esdb) {
		this.targetOffset = targetOffset;
		createRPMSymbols(rpm, esdb);
	}

	private void createRPMSymbols(RPM rpm, ExternalSymbolDB esdb) {
		for (ELFSymbolSection.ELFSymbol smb : sym.symbols) {
			if (smb.sectionIndex == id) {
				if ((smb.name == null || !smb.name.startsWith("$")) && Elf2RPMSymbolAdapter.acceptsSymType(smb.getSymType())) {
					System.out.println("Converting ELF symbol " + smb.name + " type " + smb.getSymType());
					Elf2RPMSymbolAdapter s = new Elf2RPMSymbolAdapter(smb);

					if (s.origin.sectionIndex == 0 && s.name != null && esdb.isFuncExternal(s.name)) {
						System.out.println("Extern func " + s.name + " (cur symtype: " + s.type + ")");
						s.type = RPMSymbolType.FUNCTION_ARM;
						int off = esdb.getOffsetOfFunc(s.name);
						if ((off & 1) == 1) {
							off--;
							s.type = RPMSymbolType.FUNCTION_THM;
						}
						s.address = new RPMSymbolAddress(rpm, RPMSymbolAddress.RPMAddrType.GLOBAL, off);
					} else {
						if (id == 0) {
							//	System.out.println("NONEXTERN FUNC IN EXTERN SEGMENT " + smb.getName());
							s.address = new RPMSymbolAddress(rpm, s.name); //null address. Name will be hashed, so it can be stripped freely.
							if (s.name != null) {
								s.addAttribute(Elf2RPMSymbolAdapter.RPM_SYMATTR_IMPORT);
							}
						} else {
							int sval = smb.value + targetOffset;
							if ((sval & 1) != 0 && s.type == RPMSymbolType.FUNCTION_ARM) {
								s.type = RPMSymbolType.FUNCTION_THM;
								sval--;
							}
							s.address = new RPMSymbolAddress(rpm, RPMSymbolAddress.RPMAddrType.LOCAL, sval);
						}
					}
					rpmSymbols.add(s);
				} else {
					System.out.println("Skipping symbol " + smb.name + " of type " + smb.getSymType());
				}
			}
		}
	}
}
