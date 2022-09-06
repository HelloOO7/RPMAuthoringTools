package rpm.elfconv;

import xstandard.arm.elf.format.ELF;
import java.io.IOException;
import rpm.format.rpm.RPM;

public interface IElf2RpmConverter {
	public RPM getRPM(ELF elf, ExternalSymbolDB esdb) throws IOException;
}
