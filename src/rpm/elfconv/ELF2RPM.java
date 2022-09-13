
package rpm.elfconv;

import xstandard.arm.elf.format.ELF;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import rpm.elfconv.rel.ETRel2RPMConverter;
import rpm.elfconv.exec.ETExec2RPMConverter;
import rpm.format.rpm.RPM;
import xstandard.fs.FSFile;

/**
 *
 */
public class ELF2RPM {
	public static RPM getRPM(FSFile elfFile, ExternalSymbolDB esdb){
		try {
			ELF elf = new ELF(elfFile);
			
			IElf2RpmConverter conv = null;
			
			switch (elf.header.type){
				case EXEC:
					conv = new ETExec2RPMConverter();
					break;
				case REL:
					conv = new ETRel2RPMConverter();
					break;
			}
			
			if (conv != null){
				RPM rpm = conv.getRPM(elf, esdb);
				rpm.updateCodeImageForBaseAddr(); //relocate to base 0 for better analysis
				return rpm;
			}
		} catch (IOException ex) {
			Logger.getLogger(ELF2RPM.class.getName()).log(Level.SEVERE, null, ex);
		}
		return null;
	}
}
