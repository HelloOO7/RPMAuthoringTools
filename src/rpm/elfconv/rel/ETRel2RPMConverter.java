package rpm.elfconv.rel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rpm.elfconv.ExternalSymbolDB;
import xstandard.arm.elf.SectionType;
import xstandard.arm.elf.format.ELF;
import xstandard.arm.elf.format.ELFARMRelocationType;
import xstandard.arm.elf.format.sections.ELFRelocationSectionBase;
import xstandard.arm.elf.format.sections.ELFSection;
import xstandard.arm.elf.format.sections.ELFSymbolSection;
import rpm.format.rpm.RPM;
import rpm.format.rpm.RPMRelocation;
import rpm.format.rpm.RPMRelocationSource;
import rpm.format.rpm.RPMSymbol;
import rpm.elfconv.IElf2RpmConverter;
import rpm.format.rpm.RPMRelTargetType;
import rpm.format.rpm.RPMRelocationTarget;
import rpm.format.rpm.RPMSymbolAddress;
import rpm.format.rpm.RPMSymbolType;
import xstandard.io.base.impl.ext.data.DataIOStream;
import xstandard.math.MathEx;

/**
 *
 */
public class ETRel2RPMConverter implements IElf2RpmConverter {

	@Override
	public RPM getRPM(ELF elf, ExternalSymbolDB esdb) throws IOException {
		RPM rpm = new RPM();

		DataIOStream io = elf.getSourceFile().getDataIOStream();

		List<RelElfSection> sections = new ArrayList<>();

		Map<ELFSection, ELFRelocationSectionBase<? extends ELFRelocationSectionBase.RelocationEntry>> relSections = new HashMap<>();

		ELFSymbolSection symbs = elf.sectionsByClass(ELFSymbolSection.class).get(0);
		List<Elf2RPMSymbolAdapter> rpmSymbols = new ArrayList<>();

		for (ELFSymbolSection.ELFSymbol sym : symbs.symbols) { //convert absolute symbols
			if (sym.getSpecialSectionIndex() == ELFSymbolSection.ELFSpecialSectionIndex.ABS && Elf2RPMSymbolAdapter.acceptsSymType(sym.getSymType())) {
				Elf2RPMSymbolAdapter adapter = new Elf2RPMSymbolAdapter(sym);
				adapter.address = new RPMSymbolAddress(rpm, RPMSymbolAddress.RPMAddrType.GLOBAL, sym.value);
				if ((sym.value & 1) != 0 && adapter.type == RPMSymbolType.FUNCTION_ARM) {
					makeSymThmFunc(adapter);
				}
				rpmSymbols.add(adapter);
			}
		}

		for (ELFSection sec : elf.sections()) {
			SectionType compatibleType = SectionType.getSectionTypeFromElf(sec.header);

			if (compatibleType != null) {
				sections.add(new RelElfSection(elf, sec, symbs, io));
			} else if (sec instanceof ELFRelocationSectionBase) {
				ELFRelocationSectionBase rel = (ELFRelocationSectionBase) sec;
				relSections.put(elf.getSectionByIndex(rel.getRelocatedSegmentNo()), rel);
			} else {
				System.out.println("Skipping section " + sec.header.name + " (idx " + elf.getSectionIndex(sec) + ")");
			}
		}

		int offs = 0;
		for (RelElfSection sec : sections) {
			sec.prepareForRPM(rpm, offs, esdb);
			rpmSymbols.addAll(sec.rpmSymbols);
			offs += sec.length;
			offs = MathEx.padInteger(offs, Integer.BYTES);
		}

		for (Map.Entry<ELFSection, ELFRelocationSectionBase<? extends ELFRelocationSectionBase.RelocationEntry>> re : relSections.entrySet()) {
			ELFRelocationSectionBase<? extends ELFRelocationSectionBase.RelocationEntry> relocation = re.getValue();
			RelElfSection sec = findSectionById(sections, relocation.getRelocatedSegmentNo());

			if (sec != null) {
				//System.out.println("Processing relocation section " + relocation.getName());
				for (ELFRelocationSectionBase.RelocationEntry e : relocation.entries) {
					ELFARMRelocationType relType = e.getRelType();
					if (relType == ELFARMRelocationType.R_ARM_NONE) {
						continue;
					}

					int relocOffs = e.offset;
					int rpmRelocOffs = relocOffs + sec.targetOffset;
					int elfRelocOffs = relocOffs + sec.sourceOffset;

					RPMRelocation rel = new RPMRelocation();
					rel.target = new RPMRelocationTarget(rpmRelocOffs, null);
					//System.out.println("Added RPM relocation " + e.offset + " at " + Integer.toHexString(rpmRelocOffs) + " type " + e.getRelType());
					ELFSymbolSection.ELFSymbol es = symbs.symbols.get(e.getRelSymbol());

					switch (relType) {
						case R_ARM_PREL31:
						case R_ARM_ABS32: {
							io.seek(elfRelocOffs);
							int addend = io.readInt();
							if (relType == ELFARMRelocationType.R_ARM_PREL31) {
								addend = (addend << 1) >> 1;
							}
							addend += e.getAddend();
							rel.target.targetType = relType == ELFARMRelocationType.R_ARM_ABS32 ? RPMRelTargetType.OFFSET : RPMRelTargetType.OFFSET_REL31;
							RPMSymbol s = findRPMByMatchElfAddr(rpmSymbols, es, addend);

							if (s == null) {
								System.out.println("Notfound converted RPM symbol " + es.name + " addend " + addend + " shndx " + Long.toHexString(es.sectionIndex) + " at " + Integer.toHexString(relocOffs));

								s = new RPMSymbol();
								s.name = null;
								s.type = RPMSymbolType.VALUE;
								if (findSectionById(sections, es.sectionIndex) == null) {
									System.out.println("FATAL: NOTFOUND SECTION " + es.sectionIndex + " for sym " + es.name);

									for (ELFSection se : elf.sections()) {
										System.out.println("section " + se + ": " + se.header.name);
									}
								}

								s.address = new RPMSymbolAddress(rpm, RPMSymbolAddress.RPMAddrType.LOCAL, findSectionById(sections, es.sectionIndex).targetOffset + (int) es.value + addend);
								System.out.println("Notfound fixup addr " + Integer.toHexString(s.address.getAddr()));
								rpm.symbols.add(s);
							} else {
								System.out.println("Found symbol " + es.name + " (@0x" + Long.toHexString(es.value) + ")" + " of shndx " + es.sectionIndex + " at " + Integer.toHexString(s.address.getAddr()));
							}

							rel.source = new RPMRelocationSource(rpm, s);
							break;
						}
						case R_ARM_THM_PC22: {
							rel.target.targetType = RPMRelTargetType.THUMB_BRANCH_LINK;
							RPMSymbol s = findRPMByMatchElfAddr(rpmSymbols, es, 0, true);
							if (s == null) {
								throw new RuntimeException("Could not find function symbol " + es.name + " of shndx " + es.sectionIndex);
							} else {
								if (s.type != RPMSymbolType.FUNCTION_ARM) { //for notype functions, assume thumb
									makeSymThmFunc(s);
								}
								System.out.println("Got func symbol " + s.name + " type " + s.type + " add type " + s.address.getAddrType());
							}
							rel.source = new RPMRelocationSource(rpm, s);
							break;
						}
						case R_ARM_CALL:
							rel.target.targetType = RPMRelTargetType.ARM_BRANCH_LINK;
							RPMSymbol s = findRPMByMatchElfAddr(rpmSymbols, es, 0, true);
							if (s == null) {
								throw new RuntimeException("Could not find function symbol " + es.name);
							}
							rel.source = new RPMRelocationSource(rpm, s);
							break;
						default:
							System.out.println("UNSUPPORTED ARM RELOCATION TYPE: " + e.getRelType() + " at " + Integer.toHexString(elfRelocOffs));
							break;
					}

					if (rel.source != null) {
						//System.out.println("Adding relocation at ELF addr " + Integer.toHexString(elfRelocOffs));
						rpm.relocations.add(rel);
					}
				}
			}
		}

		io.close();

		DataIOStream code = new DataIOStream();
		for (RelElfSection s : sections) {
			code.seek(s.targetOffset);
			code.write(s.getBytes());
		}
		rpm.setCode(code);
		rpm.symbols.addAll(rpmSymbols);

		return rpm;
	}

	private static void makeSymThmFunc(RPMSymbol sym) {
		if (sym.type != RPMSymbolType.FUNCTION_THM) {
			sym.type = RPMSymbolType.FUNCTION_THM;
			if (!sym.isImportSymbol()) {
				sym.address.setAddr(sym.address.getAddr() & 0xFFFFFFFE);
			}
		}
	}

	private static RPMSymbol findRPMByMatchElfAddr(List<Elf2RPMSymbolAdapter> symbols, ELFSymbolSection.ELFSymbol sym, int addend) {
		return findRPMByMatchElfAddr(symbols, sym, addend, false);
	}

	private static RPMSymbol findRPMByMatchElfAddr(List<Elf2RPMSymbolAdapter> symbols, ELFSymbolSection.ELFSymbol sym, int addend, boolean needsFunc) {
		if (addend == 0) {
			for (Elf2RPMSymbolAdapter a : symbols) {
				if (a.origin == sym && a.origin.getSymType() != ELFSymbolSection.ELFSymbolType.SECTION) {
					return a;
				}
			}
		}
		int addr = (int) (sym.value + addend);
		for (Elf2RPMSymbolAdapter a : symbols) {
			if (a.origin.sectionIndex == sym.sectionIndex && a.origin.value == addr) {
				if (!needsFunc || a.type.isFunction()) {
					return a;
				}
			}
		}
		return null;
	}

	private static RelElfSection findSectionById(List<RelElfSection> l, int id) {
		for (RelElfSection s : l) {
			if (s.id == id) {
				return s;
			}
		}
		return null;
	}
}
