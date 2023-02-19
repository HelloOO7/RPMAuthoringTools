package rpm.elfconv.exec;

import xstandard.fs.accessors.DiskFile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import rpm.elfconv.ExternalSymbolDB;
import rpm.elfconv.SectionType;
import xstandard.arm.elf.format.ELF;
import xstandard.arm.elf.format.sections.ELFSection;
import xstandard.arm.elf.format.sections.ELFSymbolSection;
import rpm.elfconv.rel.Elf2RPMSymbolAdapter;
import rpm.format.rpm.RPM;
import rpm.format.rpm.RPMSymbolAddressCompat;
import rpm.format.rpm.RPMSymbolType;
import rpm.elfconv.IElf2RpmConverter;
import xstandard.io.base.impl.ext.data.DataIOStream;

/**
 *
 */
public class ETExec2RPMConverter implements IElf2RpmConverter {

	@Override
	public RPM getRPM(ELF elf, ExternalSymbolDB esdb) throws IOException {
		RPM rpm = new RPM();

		List<ExecElfSection> sections = new ArrayList<>();

		DataIOStream io = elf.getSourceFile().getDataIOStream();

		for (ELFSection sec : elf.sections()) {
			SectionType t = SectionType.getSectionTypeFromElf(sec.header);
			if (t != null) {
				sections.add(new ExecElfSection(sec, t, elf, io, rpm));
			}
		}

		io.close();

		ETExecRelocationState relocState = new ETExecRelocationState(sections, esdb);

		for (ELFSymbolSection.ELFSymbol smb : elf.sectionsByClass(ELFSymbolSection.class).get(0).symbols) {
			if (!smb.name.startsWith("$") && acceptsSymType(smb.getSymType())) {
				Elf2RPMSymbolAdapter s = new Elf2RPMSymbolAdapter(rpm, smb);
				s.name = smb.name;
				s.type = getRpmSymType(smb.getSymType());

				if (s.name != null && esdb.isFuncExternal(s.name)) {
					s.setAddress(esdb.getOffsetOfFunc(s.name), true);
				} else {
					int smbValue = (int) smb.value;
					if ((smbValue & 1) != 0 && s.type == RPMSymbolType.FUNCTION_ARM){
						s.type = RPMSymbolType.FUNCTION_THM;
						smbValue--;
					}
					s.setAddress(smbValue - relocState.getSourceSectionOffsetById(smb.sectionIndex) + relocState.getTargetSectionOffsetById(smb.sectionIndex), false);
				}
				rpm.symbols.add(s);
			}
		}

		for (ExecElfSection s : sections) {
			s.relocate(relocState);
		}

		DataIOStream out = new DataIOStream();
		for (ExecElfSection c : sections) {
			rpm.relocations.addAll(c.relocs);
			out.seek(relocState.getTargetSectionOffsetById(c.id));
			out.write(c.getBinary());
		}

		rpm.setCode(out);

		return rpm;
	}

	private static boolean acceptsSymType(ELFSymbolSection.ELFSymbolType symType) {
		switch (symType) {
			case FUNC:
			case NOTYPE:
				return true;
		}
		return false;
	}

	private static RPMSymbolType getRpmSymType(ELFSymbolSection.ELFSymbolType symType) {
		switch (symType) {
			case FUNC:
				return RPMSymbolType.FUNCTION_ARM;
			case NOTYPE:
				return RPMSymbolType.VALUE;
		}
		return null;
	}
}
