package rpm.elfconv;

import ctrmap.stdlib.arm.elf.format.ELF;
import java.io.IOException;
import rpm.format.rpm.RPM;

public interface IElf2RpmConverter {
	public RPM getRPM(ELF elf, ExternalSymbolDB esdb) throws IOException;
}
