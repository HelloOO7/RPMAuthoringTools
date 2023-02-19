package rpm.elfconv.rel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rpm.elfconv.ExternalSymbolDB;
import rpm.elfconv.SectionType;
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
import rpm.format.rpm.RPMSymbolAddressCompat;
import rpm.format.rpm.RPMSymbolType;
import xstandard.arm.ARMAssembler;
import xstandard.arm.elf.format.ELFSectionHeader;
import xstandard.arm.elf.format.sections.ELFFuncArraySection;
import xstandard.arm.elf.format.sections.ELFRelocationSection;
import xstandard.fs.accessors.DiskFile;
import xstandard.io.base.impl.ext.data.DataIOStream;
import xstandard.math.MathEx;

/**
 *
 */
public class ETRel2RPMConverter implements IElf2RpmConverter {

	@Override
	public RPM getRPM(ELF elf, ExternalSymbolDB esdb) throws IOException {
		RPM rpm = new RPM();

		DataIOStream elfIO = elf.getSourceFile().getDataIOStream();

		List<RelElfSection> sections = new ArrayList<>();

		List<ELFRelocationSectionBase<? extends ELFRelocationSectionBase.RelocationEntry>> relSections = new ArrayList<>();

		ELFSymbolSection symbs = elf.sectionsByClass(ELFSymbolSection.class).get(0);
		List<Elf2RPMSymbolAdapter> rpmSymbols = new ArrayList<>();

		for (ELFSymbolSection.ELFSymbol sym : symbs.symbols) { //convert absolute symbols
			if (sym.getSpecialSectionIndex() == ELFSymbolSection.ELFSpecialSectionIndex.ABS && Elf2RPMSymbolAdapter.acceptsSymType(sym.getSymType())) {
				Elf2RPMSymbolAdapter adapter = new Elf2RPMSymbolAdapter(rpm, sym);
				adapter.setAddress(sym.value, true);
				if ((sym.value & 1) != 0 && adapter.type == RPMSymbolType.FUNCTION_ARM) {
					makeSymThmFunc(adapter);
				}
				rpmSymbols.add(adapter);
			}
		}

		for (ELFSection sec : elf.sections()) {
			SectionType compatibleType = SectionType.getSectionTypeFromElf(sec.header);

			if (compatibleType != null) {
				sections.add(new RelElfSection(elf, sec, symbs, elfIO));
			} else if (sec instanceof ELFRelocationSectionBase) {
				ELFRelocationSectionBase rel = (ELFRelocationSectionBase) sec;
				relSections.add(rel);
			} else {
				System.out.println("Skipping section " + sec.header.name + " (idx " + elf.getSectionIndex(sec) + ")");
			}
		}

		List<RelElfSection> sectionsInOrder = new ArrayList<>();
		for (RelElfSection s : sections) {
			if (s.type == SectionType.TEXT) {
				sectionsInOrder.add(s);
			}
		}

		DataIOStream extraCode = new DataIOStream();
		RelElfSection extraSection = new RelElfSection(-2, SectionType.TEXT, symbs, extraCode);
		ELFRelocationSection extraRelSection = new ELFRelocationSection("extra");
		extraRelSection.setRelocatedSegmentNo(-2);
		Map<ELFSymbolSection.ELFSymbol, ELFSymbolSection.ELFSymbol> helperFuncMap = new HashMap<>();

		for (ELFRelocationSectionBase<? extends ELFRelocationSectionBase.RelocationEntry> sec : relSections) {
			for (ELFRelocationSectionBase.RelocationEntry rel : sec.entries) {
				if (rel.getRelType() == ELFARMRelocationType.R_ARM_JUMP24) {
					ELFSymbolSection.ELFSymbol s = symbs.symbols.get(rel.getRelSymbol());
					int realOffset = s.value;
					if (esdb.isFuncExternal(s.name)) {
						realOffset = esdb.getOffsetOfFunc(s.name);
					}
					if ((realOffset & 1) != 0) {
						if (!helperFuncMap.containsKey(s)) {
							int fromArmOffs = extraCode.getPosition();
							//target is thumb symbol - need to add helper code
							ARMAssembler.writeLDRInstruction(extraCode, 12, fromArmOffs + 8);
							ARMAssembler.writeBXInstruction(extraCode, 12);
							extraCode.writeInt(0); //allocate offset word
							ELFRelocationSectionBase.RelocationEntry re = new ELFRelocationSectionBase.RelocationEntry();
							re.offset = fromArmOffs + 8;
							re.setRelType(ELFARMRelocationType.R_ARM_ABS32);
							re.setRelSymbol(rel.getRelSymbol());
							extraRelSection.entries.add(re);
							ELFSymbolSection.ELFSymbol fromArm = new ELFSymbolSection.ELFSymbol();
							fromArm.name = "__" + s.name + "_from_arm";
							fromArm.sectionIndex = -2;
							fromArm.size = 12;
							fromArm.value = fromArmOffs;
							symbs.addSymbol(fromArm);
							helperFuncMap.put(s, fromArm);
						}
					}
				}
			}
		}
		sections.add(extraSection);

		int initArrayOffset = -1;
		int finiArrayOffset = -1;

		//Now add static ctors/dtors
		for (RelElfSection staticsec : sections) {
			if (staticsec.type == SectionType.INIT_ARRAY) {
				if (initArrayOffset == -1) {
					initArrayOffset = staticsec.targetOffset;
				}
				sectionsInOrder.add(staticsec);
			}
		}
		for (RelElfSection staticsec : sections) {
			if (staticsec.type == SectionType.FINI_ARRAY) {
				if (finiArrayOffset == -1) {
					finiArrayOffset = staticsec.targetOffset;
				}
				sectionsInOrder.add(staticsec);
			}
		}

		sectionsInOrder.add(extraSection);
		relSections.add(extraRelSection);

		//BSS follows after code
		for (RelElfSection s : sections) {
			if (s.type == SectionType.BSS || s.type == SectionType.EXTERN) {
				sectionsInOrder.add(s);
			}
		}

		int bssSize = 0;
		int codeOffs = 0;
		for (RelElfSection sec : sectionsInOrder) {
			sec.prepareForRPM(rpm, codeOffs, esdb);
			rpmSymbols.addAll(sec.rpmSymbols);
			int alignedLength = MathEx.padInteger(sec.getLength(), Integer.BYTES);
			codeOffs += alignedLength;
			if (sec.type == SectionType.BSS) {
				bssSize += alignedLength;
			}
		}
		for (Elf2RPMSymbolAdapter rpmSym : rpmSymbols) {
			ELFSymbolSection.ELFSymbol sym = rpmSym.origin;
			if (sym.getSymType() == ELFSymbolSection.ELFSymbolType.SECTION) {
				ELFSection sec = elf.getSectionByIndex(sym.sectionIndex);
				rpmSym.size = sec.header.size;
				ELFSectionHeader.ELFSectionType est = sec.header.type.getSectionType();
				if (est == ELFSectionHeader.ELFSectionType.INIT_ARRAY) {
					rpm.sinitSymbols.add(rpmSym);
				} else if (est == ELFSectionHeader.ELFSectionType.FINI_ARRAY) {
					rpm.sfiniSymbols.add(rpmSym);
				}
			}
		}

		for (ELFRelocationSectionBase<? extends ELFRelocationSectionBase.RelocationEntry> relocation : relSections) {
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

					RPMRelocation rel = new RPMRelocation();
					rel.target = new RPMRelocationTarget(rpmRelocOffs, null);
					//System.out.println("Added RPM relocation " + e.offset + " at " + Integer.toHexString(rpmRelocOffs) + " type " + e.getRelType());
					ELFSymbolSection.ELFSymbol es = sec.sym.symbols.get(e.getRelSymbol());

					switch (relType) {
						case R_ARM_PREL31:
						case R_ARM_TARGET1:
						case R_ARM_ABS32: {
							int addend = sec.read32(relocOffs);
							if (relType == ELFARMRelocationType.R_ARM_PREL31) {
								addend = (addend << 1) >> 1;
							}
							addend += e.getAddend();
							rel.target.targetType = relType == ELFARMRelocationType.R_ARM_PREL31 ? RPMRelTargetType.OFFSET_REL31 : RPMRelTargetType.OFFSET;
							Elf2RPMSymbolAdapter s = findRPMByMatchElfAddr(rpmSymbols, es, addend);

							if (s == null) {
								RPMSymbol baseSymbol = findRPMByMatchElfAddr(rpmSymbols, es, 0, true, false);
								if (baseSymbol == null) {
									throw new RuntimeException("Could not find converted symbol " + es);
								}
								ELFSymbolSection.ELFSymbol dummyES = new ELFSymbolSection.ELFSymbol(es);
								dummyES.value += addend;
								//System.out.println("Notfound converted RPM symbol " + es.name + " addend " + addend + " shndx " + Long.toHexString(es.sectionIndex) + " at " + Integer.toHexString(relocOffs));
								s = new Elf2RPMSymbolAdapter(rpm, dummyES);
								s.address = baseSymbol.address + addend;
								if (s.name != null) {
									s.name += "_+0x" + Integer.toHexString(addend);
								}
								s.type = RPMSymbolType.VALUE;

								//System.out.println("Notfound fixup addr " + Integer.toHexString(s.address.getAddr()));
								rpmSymbols.add(s);
							} else {
								//System.out.println("Found symbol " + es + " at " + Integer.toHexString(s.address.getAddr()));
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
								//System.out.println("Got func symbol " + s.name + " type " + s.type + " add type " + s.address.getAddrType());
							}
							rel.source = new RPMRelocationSource(rpm, s);
							break;
						}
						case R_ARM_CALL: {
							rel.target.targetType = RPMRelTargetType.ARM_BRANCH_LINK;
							RPMSymbol s = findRPMByMatchElfAddr(rpmSymbols, es, 0, true);
							if (s == null) {
								throw new RuntimeException("Could not find function symbol " + es.name);
							}
							rel.source = new RPMRelocationSource(rpm, s);
							break;
						}
						case R_ARM_JUMP24: {
							int insn = sec.read32(relocOffs);
							boolean isLink = (insn & (0b00000001 << 24)) != 0;
							rel.target.targetType = isLink ? RPMRelTargetType.ARM_BRANCH_LINK : RPMRelTargetType.ARM_BRANCH;
							RPMSymbol s = findRPMByMatchElfAddr(rpmSymbols, es, 0, true);
							if (s == null) {
								throw new RuntimeException("Could not find function symbol " + es.name);
							}
							rel.source = new RPMRelocationSource(rpm, s);
							if (s.type == RPMSymbolType.FUNCTION_THM) {
								System.out.println("Creating ARM->Thumb helper function for " + s.name);
								ELFSymbolSection.ELFSymbol fromArm = helperFuncMap.get(es);
								if (fromArm == null) {
									throw new NullPointerException();
								}
								rel.source.symb = findRPMByMatchElfAddr(rpmSymbols, fromArm, 0);
							}
							break;
						}
						default:
							throw new RuntimeException("UNSUPPORTED ARM RELOCATION TYPE: " + e.getRelType() + " at " + Integer.toHexString(e.offset));
					}

					if (rel.source != null) {
						//System.out.println("Adding relocation at ELF addr " + Integer.toHexString(elfRelocOffs));
						rpm.relocations.add(rel);
					}
				}
			}
		}

		elfIO.close();

		DataIOStream code = new DataIOStream();
		for (RelElfSection s : sectionsInOrder) {
			if (s.type == SectionType.TEXT || s.isInitOrFiniArray()) {
				code.seek(s.targetOffset);
				s.write(code);
			}
		}
		code.write(extraCode.toByteArray());
		rpm.setCode(code);
		rpm.bssSize = bssSize;
		rpm.symbols.addAll(rpmSymbols);

		return rpm;
	}

	private static void makeSymThmFunc(RPMSymbol sym) {
		if (sym.type != RPMSymbolType.FUNCTION_THM) {
			sym.type = RPMSymbolType.FUNCTION_THM;
			if (!sym.isImportSymbol()) {
				sym.address &= 0xFFFFFFFE;
			}
		}
	}

	private static Elf2RPMSymbolAdapter findRPMByMatchElfAddr(List<Elf2RPMSymbolAdapter> symbols, ELFSymbolSection.ELFSymbol sym, int addend) {
		return findRPMByMatchElfAddr(symbols, sym, addend, false);
	}

	private static Elf2RPMSymbolAdapter findRPMByMatchElfAddr(List<Elf2RPMSymbolAdapter> symbols, ELFSymbolSection.ELFSymbol sym, int addend, boolean needsFunc) {
		return findRPMByMatchElfAddr(symbols, sym, addend, false, needsFunc);
	}

	private static Elf2RPMSymbolAdapter findRPMByMatchElfAddr(List<Elf2RPMSymbolAdapter> symbols, ELFSymbolSection.ELFSymbol sym, int addend, boolean allowSectionSymbols, boolean needsFunc) {
		if (addend == 0) {
			for (Elf2RPMSymbolAdapter a : symbols) {
				if (a.origin == sym && (allowSectionSymbols || a.origin.getSymType() != ELFSymbolSection.ELFSymbolType.SECTION)) {
					return a;
				}
			}
		} else {
			int addr = (int) (sym.value + addend);
			for (Elf2RPMSymbolAdapter a : symbols) {
				if (a.origin.sectionIndex == sym.sectionIndex && a.origin.value == addr) {
					if (!needsFunc || a.type.isFunction()) {
						return a;
					}
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
