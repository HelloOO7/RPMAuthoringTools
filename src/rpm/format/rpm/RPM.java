package rpm.format.rpm;

import ctrmap.stdlib.arm.ARMAssembler;
import ctrmap.stdlib.fs.FSFile;
import ctrmap.stdlib.text.FormattingUtils;
import ctrmap.stdlib.io.InvalidMagicException;
import ctrmap.stdlib.io.util.StringIO;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import ctrmap.stdlib.arm.ThumbAssembler;
import ctrmap.stdlib.fs.accessors.DiskFile;
import ctrmap.stdlib.gui.file.ExtensionFilter;
import ctrmap.stdlib.io.base.iface.IOStream;
import ctrmap.stdlib.io.base.impl.ext.data.DataIOStream;
import ctrmap.stdlib.io.structs.StringTable;
import ctrmap.stdlib.io.structs.TemporaryOffset;
import ctrmap.stdlib.io.structs.TemporaryValue;
import ctrmap.stdlib.math.MathEx;
import ctrmap.stdlib.util.ArraysEx;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Relocatable Program Module
 *
 * Format handler.
 */
public class RPM {

	public static final ExtensionFilter EXTENSION_FILTER = new ExtensionFilter("Relocatable Program Module", "*.rpm");

	public static final String RPM_PROLOG_MAGIC = "RPM0";
	public static final String RPM_DLLEXEC_HEADER_MAGIC = "DLXH";

	public static final String RPM_LEGACY_MAGIC = "RPM";
	public static final String SYM_MAGIC = "SYM0";
	public static final String STR_MAGIC = "STR0";
	public static final String REL_MAGIC = "REL0";

	public static final String INFO_MAGIC = "INFO";
	public static final String META_MAGIC = "META";

	public static final int RPM_PROLOG_SIZE = 0x20;
	public static final int RPM_DLLEXEC_HEADER_SIZE = 0x10;

	public static final int RPM_PADDING = 0x10;

	public static final int RPM_FOOTER_SIZE_LEGACY = 0x10;
	public static final int RPM_FOOTER_SIZE = 0x20;

	public static final int RPM_INFO_HEADER_SIZE = 0x20;

	private int baseAddress = -1;
	public List<RPMSymbol> symbols = new ArrayList<>();
	public List<RPMRelocation> relocations = new ArrayList<>();
	public RPMMetaData metaData = new RPMMetaData();

	private RPMExternalSymbolResolver extResolver;

	private DataIOStream code;

	public RPM(FSFile fsf) {
		this(fsf.getBytes());
	}

	public DataIOStream getCodeStream() {
		return code;
	}

	public int getBaseAddress() {
		return baseAddress;
	}

	public int getCodeSegmentBase() {
		return baseAddress + RPM_PROLOG_SIZE;
	}

	public RPM(IOStream io, int startPos, int endPos) {
		this(io, null, startPos, endPos);
	}

	/**
	 * Reads a program module from a stream.
	 *
	 * @param io The stream to read from.
	 * @param fourCC Four character magic to validate files with.
	 * @param startPos Starting position of the module.
	 * @param endPos Ending position of the module.
	 */
	public RPM(IOStream io, String fourCC, int startPos, int endPos) {
		try {
			if (fourCC == null) {
				fourCC = RPM_PROLOG_MAGIC;
			}
			if (endPos == -1) {
				endPos = io.getLength();
			}

			RPMReader reader = new RPMReader(io);

			int version = 0xFF;

			if (startPos != -1) {
				reader.seek(startPos);
			}
			if (StringIO.checkMagic(reader, fourCC)) { //dlxf prolog
				int fileSize = reader.readInt();
				reader.seek(reader.readInt() + startPos);
				if (!StringIO.checkMagic(reader, RPM_DLLEXEC_HEADER_MAGIC)) {
					throw new InvalidMagicException("Incorrect RPM DllExec header!");
				}
			} else { //legacy
				reader.seek(endPos - RPM_FOOTER_SIZE);
				if (!StringIO.checkMagic(reader, RPM_LEGACY_MAGIC)) {
					reader.seek(endPos - RPM_FOOTER_SIZE_LEGACY);
					if (!StringIO.checkMagic(reader, RPM_LEGACY_MAGIC)) {
						throw new InvalidMagicException("Not an RPM file!");
					}
				}
				version = reader.read();
			}

			if (startPos != 0) {
				reader.setBase(-startPos);
			}

			int infoSectionOffset = -1;
			if (version != 0xFF) {
				version = version - '0';
				reader.setVersion(version);
			} else {
				version = reader.readInt();
				reader.setVersion(version);
				if (reader.versionOver(RPMRevisions.REV_PRODUCT_INFO)) {
					if (reader.versionOver(RPMRevisions.REV_INFO_SECTION)) {
						infoSectionOffset = reader.readInt();
						reader.skipBytes(4);
					} else {
						//product ID and product version fields. DISCONTINUED.
						reader.skipBytes(8);
					}
				} else {
					reader.skipBytes(8);
				}
			}
			int symbolsOffset;
			int relocationsOffset;
			int stringsOffset = -1;
			int codeOffset = 0;
			int codeSize;
			int metaDataOffset = -1;

			if (infoSectionOffset == -1) {
				symbolsOffset = reader.readInt();
				relocationsOffset = reader.readInt();
				if (reader.versionOver(RPMRevisions.REV_SMALL_SYMBOLS)) {
					stringsOffset = reader.readInt();
				}
				codeSize = reader.readInt();
			} else {
				//RPM v. REV_INFO_SECTION+
				reader.seek(infoSectionOffset);
				if (!StringIO.checkMagic(reader, INFO_MAGIC)) {
					throw new InvalidMagicException("INFO section not present!");
				}
				symbolsOffset = reader.readInt();
				relocationsOffset = reader.readInt();
				stringsOffset = reader.readInt();
				if (reader.versionOver(RPMRevisions.REV_INDEPENDENT_CODE_SEG)) {
					codeOffset = reader.readInt();
				}
				codeSize = reader.readInt();
				metaDataOffset = reader.readInt();
			}

			if (stringsOffset >= 0) {
				reader.seek(stringsOffset);
				if (!StringIO.checkMagic(reader, STR_MAGIC)) {
					throw new InvalidMagicException("STR section not present!");
				}
				reader.setStrTableOffsHere();
			}

			if (metaDataOffset >= 0) {
				reader.seek(metaDataOffset);
				if (!StringIO.checkMagic(reader, META_MAGIC)) {
					throw new InvalidMagicException("META section not present!");
				}
				metaData.readMetaData(reader);
			}

			if (symbolsOffset >= 0) {
				reader.seek(symbolsOffset);
				if (!StringIO.checkMagic(reader, SYM_MAGIC)) {
					throw new InvalidMagicException("SYM section not present!");
				}
				if (reader.versionOver(RPMRevisions.REV_EXTERN_LISTS)) {
					int symtabExternModuleListOffset = reader.readInt();
				}
				int firstExportSymbolIdx = -1;
				int firstImportSymbolIdx = -1;
				int exportSymbolCount = 0;
				int importSymbolCount = 0;
				int exportSymbolHashTableOffset = -1;
				if (reader.versionOver(RPMRevisions.REV_IMPORT_EXPORT_SYMBOL_HASHTABLES)) {
					firstExportSymbolIdx = reader.readUnsignedShort();
					exportSymbolCount = reader.readUnsignedShort();
					firstImportSymbolIdx = reader.readUnsignedShort();
					importSymbolCount = reader.readUnsignedShort();
					exportSymbolHashTableOffset = reader.readInt();
				}
				int symbolCount = reader.aligned() ? reader.readInt() : reader.readUnsignedShort();
				for (int i = 0; i < symbolCount; i++) {
					symbols.add(new RPMSymbol(this, reader));
				}

				if (firstExportSymbolIdx != -1) {
					int exportSymbolEnd = firstExportSymbolIdx + exportSymbolCount;

					int[] hashTable = new int[exportSymbolEnd - firstExportSymbolIdx];
					if (exportSymbolHashTableOffset != -1) {
						reader.seek(exportSymbolHashTableOffset);
						for (int i = 0; i < hashTable.length; i++) {
							hashTable[i] = reader.readInt();
						}
					}

					for (int i = firstExportSymbolIdx, hashIdx = 0; i < exportSymbolEnd; i++, hashIdx++) {
						RPMSymbol s = symbols.get(i);
						s.nameHash = hashTable[hashIdx];
					}
				}
			}

			if (relocationsOffset >= 0) {
				reader.seek(relocationsOffset);
				if (!StringIO.checkMagic(reader, REL_MAGIC)) {
					throw new InvalidMagicException("REL section not present!");
				}
				baseAddress = reader.readInt();
				if (reader.versionOver(RPMRevisions.REV_SEPARATE_RELOCATIONS)) {
					int internalRelocationsOffs = reader.readInt();
					int internalImportRelocationsOffs = -1;
					if (reader.versionOver(RPMRevisions.REV_IMPORT_RELOCATION_LIST)) {
						internalImportRelocationsOffs = reader.readInt();
					}
					int externalRelocationsOffs = reader.readInt();
					int relocExternModuleListOffset = reader.readInt();

					readRelocationLists(this, reader, relocations, readExternModuleList(reader, relocExternModuleListOffset), 
						internalImportRelocationsOffs, 
						internalRelocationsOffs, 
						externalRelocationsOffs
					);
				} else {
					String[] externModuleList = null;
					if (reader.versionOver(RPMRevisions.REV_EXTERN_LISTS)) {
						int relocExternModuleListOffset = reader.readInt();
						externModuleList = readExternModuleList(reader, relocExternModuleListOffset);
					}
					int relocationCount = reader.readInt();
					for (int i = 0; i < relocationCount; i++) {
						relocations.add(new RPMRelocation(reader, this, externModuleList));
					}
				}
			}

			byte[] codeArr = new byte[codeSize];
			if (codeSize != 0) {
				reader.seek(codeOffset);
				reader.read(codeArr);
			}

			this.code = new DataIOStream(codeArr);
			setBaseAddrNoUpdateBytes(baseAddress);
		} catch (IOException ex) {
			Logger.getLogger(RPM.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	public RPM(byte[] bytes) {
		this(new DataIOStream(bytes), 0, bytes.length);
	}

	public RPM() {
		code = new DataIOStream();
		setBaseAddrNoUpdateBytes(0);
	}

	private static String[] readExternModuleList(RPMReader reader, int offset) throws IOException {
		reader.checkpoint();
		reader.seek(offset);
		int count = reader.readUnsignedShort();
		String[] ret = new String[count];
		for (int i = 0; i < count; i++) {
			ret[i] = reader.readStringWithAddress();
		}
		reader.resetCheckpoint();
		return ret;
	}
	
	private static void readRelocationLists(RPM rpm, RPMReader reader, List<RPMRelocation> dest, String[] externModuleList, int... listOffsets) throws IOException {
		for (int lo : listOffsets) {
			if (lo != -1) {
				reader.seek(lo);
				int count = reader.readInt();
				for (int i = 0; i < count; i++) {
					dest.add(new RPMRelocation(reader, rpm, externModuleList));
				}
			}
		}
	}

	public static RPM createPartialHandle(RPM source, boolean mirrorCode, boolean mirrorSymAndRel, boolean mirrorMeta) {
		RPM rpm = new RPM();
		if (mirrorCode) {
			rpm.code = source.code;
		} else {
			rpm.code = new DataIOStream();
		}
		if (mirrorSymAndRel) {
			rpm.symbols = source.symbols;
			rpm.relocations = source.relocations;
		}
		if (mirrorMeta) {
			rpm.metaData = source.metaData;
		}
		rpm.setBaseAddrNoUpdateBytes(source.baseAddress);
		return rpm;
	}

	/**
	 * Checks if a FSFile contains an RPM.
	 *
	 * @param f The FSFile.
	 * @return True if the file can be read as an RPM.
	 */
	public static boolean isRPM(FSFile f) {
		if (f.isFile() && f.length() > 0x10) {
			try {
				DataIOStream io = f.getDataIOStream();

				boolean rsl = StringIO.checkMagic(io, RPM_PROLOG_MAGIC);

				if (!rsl) {
					io.seek(f.length() - RPM_FOOTER_SIZE);
					rsl = StringIO.checkMagic(io, RPM_LEGACY_MAGIC);
					if (!rsl) {
						io.seek(f.length() - RPM_FOOTER_SIZE_LEGACY);
						rsl = StringIO.checkMagic(io, RPM_LEGACY_MAGIC);
					}
				}

				io.close();
				return rsl;
			} catch (IOException ex) {
				Logger.getLogger(RPM.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return false;
	}

	/**
	 * Merges the code, symbols and relocations from another RPM.
	 *
	 * @param source The RPM to merge.
	 */
	public void merge(RPM source) {
		try {
			int base;
			if (source.code.getRawLength() > 0) {
				base = MathEx.padInteger(code.getRawLength(), 4);
				code.seekUnbased(base);
				code.write(source.code.toByteArray());
			} else {
				base = 0;
			}

			for (RPMSymbol sym : symbols) {
				if (sym.isImportSymbol()) {
					if (sym.name != null) {
						System.out.println("Symbol " + sym + " is null, searching for imports...");
						RPMSymbol newSym = source.getSymbol(sym.name);
						if (newSym != null && !newSym.isImportSymbol()) {
							sym.clearAttribute(RPMSymbol.RPM_SYMATTR_IMPORT);
							sym.address = new RPMSymbolAddress(this, newSym.address);
							sym.address.setAddr(sym.address.getAddr() + base);
							System.out.println("Imported symbol " + newSym + " to " + sym);
						}
					}
				}
			}

			Map<RPMSymbol, RPMSymbol> oldToNewSymbolMap = new HashMap<>();

			for (RPMSymbol symbol : source.symbols) {
				RPMSymbol newSymbol = new RPMSymbol(this, symbol);
				if (newSymbol.name != null) {
					RPMSymbol existingSymbol = getSymbol(newSymbol.name);
					if (existingSymbol != null) {
						System.out.println("Found symbol in merged RPM: " + existingSymbol + ", replacing " + symbol);
						oldToNewSymbolMap.put(symbol, existingSymbol);
						continue;
					}
				}
				if (newSymbol.isLocal()) {
					newSymbol.address.setAddr(base + newSymbol.address.getAddr());
				}
				symbols.add(newSymbol);
				oldToNewSymbolMap.put(symbol, newSymbol);
			}

			for (RPMRelocation rel : source.relocations) {
				RPMRelocation newRel = new RPMRelocation(this, rel, oldToNewSymbolMap);
				if (newRel.target.isInternal()) {
					newRel.target.address += base;
				}
				relocations.add(newRel);
			}
		} catch (IOException ex) {
			Logger.getLogger(RPM.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Writes a linker address map of this RPM's symbols to a file.
	 *
	 * @param fsf The FSFile to write into.
	 */
	public void writeMAPToFile(FSFile fsf) {
		PrintStream out = new PrintStream(fsf.getNativeOutputStream());

		for (RPMSymbol symb : symbols) {
			if (!symb.isImportSymbol()) {
				int addr = symb.address.getAddrAbs();
				if (addr < baseAddress || addr > baseAddress + code.getLength()) {
					//continue; //external symbol
				}

				if (symb.type == RPMSymbolType.FUNCTION_THM) {
					addr++;
				}

				out.print("0000:");
				out.print(FormattingUtils.getStrWithLeadingZeros(8, Integer.toHexString(addr)));
				out.print("        ");
				out.println(symb.name == null ? "nullsym_" + getSymbolNo(symb) : symb.name);
			}
		}

		out.close();
	}

	public List<RPMRelocation> getExternalRelocations() {
		List<RPMRelocation> l = new ArrayList<>();
		for (RPMRelocation r : relocations) {
			if (r.target.isExternal()) {
				l.add(r);
			}
		}
		return l;
	}

	public RPMRelocation findExternalRelocationByAddress(String module, int address) {
		for (RPMRelocation r : relocations) {
			if (r.target.isExternal()) {
				if (r.target.module.equals(module) && r.target.address == address) {
					return r;
				}
			}
		}
		return null;
	}

	public void setExternalRelocations(List<RPMRelocation> l) {
		for (int i = 0; i < relocations.size(); i++) {
			if (relocations.get(i).target.isExternal()) {
				relocations.remove(i);
				i--;
			}
		}
		relocations.addAll(l);
	}

	public int getExternalRelocationCount() {
		int count = 0;
		for (RPMRelocation rel : relocations) {
			if (rel.target.isExternal()) {
				count++;
			}
		}
		return count;
	}

	public void setCode(DataIOStream buf) {
		code = buf;
		setBaseAddrNoUpdateBytes(baseAddress);
	}

	/**
	 * Relocates the code buffer to the given base offset and returns the compiled binary, then relocates it
	 * back.
	 *
	 * @param baseOfs Offset base.
	 * @return
	 */
	public byte[] getBytesForBaseOfs(int baseOfs) {
		byte[] origCode = code.toByteArray();
		updateBytesForBaseAddr(baseOfs);
		byte[] bytes = getBytes();
		try {
			code.seek(0);
			code.write(origCode);
		} catch (IOException ex) {
			Logger.getLogger(RPM.class.getName()).log(Level.SEVERE, null, ex);
		}
		return bytes;
	}

	/**
	 * Calculates the exact size of the RPM image.
	 *
	 * @return
	 */
	public int getByteSize() {
		int size = RPM_PROLOG_SIZE;

		size += code.getRawLength();
		size = MathEx.padInteger(size, RPM_PADDING);

		size += RPM_DLLEXEC_HEADER_SIZE;
		size += RPM_INFO_HEADER_SIZE;

		List<String> strings = new ArrayList<>();

		if (metaData.getValueCount() > 0) {
			size += 4; //META magic
			size += 4; //metadata count
			size += metaData.getByteSize();
			metaData.addStrings(strings);
			size = MathEx.padInteger(size, RPM_PADDING);
		}

		for (RPMSymbol s : symbols) {
			s.addStrings(strings);
		}
		for (RPMRelocation r : relocations) {
			r.target.addStrings(strings);
		}

		if (!strings.isEmpty()) {
			size += 4; //STR0 magic
			for (String s : strings) {
				size += s.length() + 1;
			}
			size = MathEx.padInteger(size, RPM_PADDING);
		}

		if (!symbols.isEmpty()) {
			size += 4; //SYM0 magic
			size += 4; //ExternModules
			size += 4; //FirstExportSymbolIdx
			size += 4; //FirstImportSymbolIdx
			size += 4; //ExportSymbolHashTable
			size += 4; //SymbolCount
			for (RPMSymbol s : symbols) {
				size += s.getByteSize();
			}
			size = MathEx.padInteger(size, 4);
			for (RPMSymbol s : symbols) {
				if (s.isExportSymbol()) {
					size += 4; //sizeof(RPM_NAMEHASH)
				}
			}
			size = MathEx.padInteger(size, RPM_PADDING);
		}

		if (!relocations.isEmpty() || baseAddress != 0) {
			List<String> relocExternModuleNames = new ArrayList<>();

			size += 4; //REL0 magic
			size += 4; //base address
			size += 4; //internal relocations offset
			size += 4; //internal import relocations offset
			size += 4; //external relocations offset
			size += 4; //external relocation module list offset

			size += 4; //relocation list size - external relocations
			for (RPMRelocation rel : relocations) {
				if (rel.target.isExternal()) {
					size += rel.getSize();
					if (!relocExternModuleNames.contains(rel.target.module)) {
						ArraysEx.addIfNotNullOrContains(strings, rel.target.module);
						relocExternModuleNames.add(rel.target.module);
					}
				}
			}
			size = MathEx.padInteger(size, 4);

			size += 2 + relocExternModuleNames.size() * 2; //external relocation module list

			size = MathEx.padInteger(size, 4);

			size += 4; //relocation list size - internal import relocations

			for (RPMRelocation rel : relocations) {
				if (isInternalImportSymbolRel(rel)) {
					size += rel.getSize();
				}
			}

			size = MathEx.padInteger(size, 4);

			size += 4; //relocation list size - internal relocations
			for (RPMRelocation rel : relocations) {
				if (isInternalNonImportSymbolRel(rel)) {
					size += rel.getSize();
				}
			}

			size = MathEx.padInteger(size, RPM_PADDING);
		}

		return size;
	}

	/**
	 * Makes all symbol names in this RPM null.
	 */
	public void stripSymbolNames() {
		for (RPMSymbol s : symbols) {
			if (s.isExportSymbol()) {
				s.updateNameHash();
				//System.out.println("export symbol " + s.name + " hash " + Integer.toHexString(s.nameHash));
			} else if (s.isImportSymbol()) {
				System.out.println("import symbol " + s.name + " hash " + Integer.toHexString(s.address.getNameHash()));
			}
			s.name = null;
		}
	}

	public byte[] getBytes() {
		return getBytes(RPM_PROLOG_MAGIC);
	}

	/**
	 * Writes the RPM image into a byte array.
	 *
	 * @param ident User-defined magic signature for the file format.
	 * @return
	 */
	public byte[] getBytes(String ident) {
		try {
			RPMWriter ba = new RPMWriter();
			ba.setAlignEnable(true);

			ba.writeStringUnterminated(ident);
			TemporaryValue fileSize = new TemporaryValue(ba);
			TemporaryOffset rpmDataOffset = new TemporaryOffset(ba);
			ba.writeInt(0); //m_ReserveFlags
			//Pointers may be 32 or 64-bit
			//We leave 16 bytes empty for the runtime pointer fields to warrant for 8-byte pointer sizes
			ba.writeLong(0); //m_PrevModule
			ba.writeLong(0); //m_NextModule

			int codeOffset = ba.getPosition();
			ba.write(code.toByteArray());
			int codeSize = code.getRawLength();
			ba.pad(RPM_PADDING);

			//HEADER
			rpmDataOffset.setHere();
			ba.writeStringUnterminated(RPM_DLLEXEC_HEADER_MAGIC);
			ba.writeInt(RPMRevisions.REV_CURRENT);//Format version
			TemporaryOffset infoOffs = new TemporaryOffset(ba);
			ba.writeInt(0); //padding

			//INFO section
			infoOffs.setHere();
			ba.writeStringUnterminated(INFO_MAGIC); //4
			TemporaryOffset symbOffset = new TemporaryOffset(ba); //8
			TemporaryOffset relOffset = new TemporaryOffset(ba); //12
			TemporaryOffset stringsOffset = new TemporaryOffset(ba); //16
			ba.writeInt(codeOffset); //20
			ba.writeInt(codeSize); //24
			TemporaryOffset metaDataOffset = new TemporaryOffset(ba); //28
			ba.writeInt(0); //reserved values - 32 bytes total

			StringTable strings = new StringTable(ba, true, true);

			//Metadata
			if (metaData.getValueCount() != 0) {
				metaDataOffset.setHere();
				ba.writeStringUnterminated(META_MAGIC);
				metaData.writeMetaData(ba, strings);
				ba.pad(RPM_PADDING);
			} else {
				metaDataOffset.set(-1);
			}

			sortSymbolsForExport();

			//Pre-populate string table
			for (RPMSymbol sym : symbols) {
				strings.putString(sym.name);
			}
			for (RPMRelocation rel : relocations) {
				rel.registStrings(strings);
			}

			//String table
			if (strings.getStringCount() != 0) {
				stringsOffset.setHere();
				ba.writeStringUnterminated(STR_MAGIC);
				strings.writeTable();
				strings.forbidFurtherWriting();
				ba.pad(RPM_PADDING);
			} else {
				stringsOffset.set(-1);
			}

			//Symbol table
			if (!symbols.isEmpty()) {
				int firstExportSymbolIdx = -1;
				int firstImportSymbolIdx = -1;
				int exportSymbolCount = 0;
				int importSymbolCount = 0;

				for (int i = 0; i < symbols.size(); i++) {
					if (symbols.get(i).isImportSymbol()) {
						if (firstImportSymbolIdx == -1) {
							firstImportSymbolIdx = i;
						}
						importSymbolCount++;
					}
					if (symbols.get(i).isExportSymbol()) {
						if (firstExportSymbolIdx == -1) {
							firstExportSymbolIdx = i;
						}
						exportSymbolCount++;
					}
				}

				symbOffset.setHere();
				ba.writeStringUnterminated(SYM_MAGIC);
				TemporaryOffset symExternModuleListOffs = new TemporaryOffset(ba);
				ba.writeShort(firstExportSymbolIdx);
				ba.writeShort(exportSymbolCount);
				ba.writeShort(firstImportSymbolIdx);
				ba.writeShort(importSymbolCount);
				TemporaryOffset exportSymbolHashTableOffs = new TemporaryOffset(ba);
				ba.writeInt(symbols.size());
				for (RPMSymbol sym : symbols) {
					sym.write(ba, strings);
				}
				symExternModuleListOffs.set(0); //null for now
				ba.pad(4);
				if (firstExportSymbolIdx != -1) {
					exportSymbolHashTableOffs.setHere();
					for (int symIdx = firstExportSymbolIdx; symIdx < symbols.size(); symIdx++) {
						RPMSymbol sym = symbols.get(symIdx);
						sym.updateNameHash();
						ba.writeInt(sym.nameHash);
						if (!sym.isExportSymbol()) {
							break;
						}
					}
				}
				ba.pad(RPM_PADDING);
			} else {
				symbOffset.set(-1);
			}

			//Relocation table
			if (!relocations.isEmpty() || baseAddress != 0) {
				relOffset.setHere();
				ba.writeStringUnterminated(REL_MAGIC);
				ba.writeInt(baseAddress);

				List<String> relocExternModuleNames = new ArrayList<>();

				TemporaryOffset internalRelocationsOffs = new TemporaryOffset(ba);
				TemporaryOffset internalImportRelocationsOffs = new TemporaryOffset(ba);
				TemporaryOffset externalRelocationsOffs = new TemporaryOffset(ba);
				TemporaryOffset relocExternModuleListOffs = new TemporaryOffset(ba);

				externalRelocationsOffs.setHere();
				TemporaryValue externalRelocationsCountValue = new TemporaryValue(ba);
				int externalRelocationsCount = 0;
				for (RPMRelocation rel : relocations) {
					if (rel.target.isExternal()) {
						rel.write(ba, strings, relocExternModuleNames);
						externalRelocationsCount++;
					}
				}
				externalRelocationsCountValue.set(externalRelocationsCount);

				ba.pad(4);
				relocExternModuleListOffs.setHere();
				ba.writeShort(relocExternModuleNames.size());
				for (String str : relocExternModuleNames) {
					strings.putStringOffset(str);
				}

				ba.pad(4);

				internalImportRelocationsOffs.setHere();
				TemporaryValue internalImportRelocationsCountValue = new TemporaryValue(ba);
				int internalImportRelocationsCount = 0;
				for (RPMRelocation rel : relocations) {
					if (isInternalImportSymbolRel(rel)) {
						rel.write(ba, strings, null);
						internalImportRelocationsCount++;
					}
				}
				internalImportRelocationsCountValue.set(internalImportRelocationsCount);
				ba.pad(4);

				internalRelocationsOffs.setHere();
				TemporaryValue internalRelocationsCountValue = new TemporaryValue(ba);
				int internalRelocationsCount = 0;
				for (RPMRelocation rel : relocations) {
					if (isInternalNonImportSymbolRel(rel)) {
						rel.write(ba, strings, null);
						internalRelocationsCount++;
					}
				}
				internalRelocationsCountValue.set(internalRelocationsCount);

				ba.pad(RPM_PADDING);
			} else {
				relOffset.set(-1);
			}

			fileSize.set(ba.getLength());

			ba.close();
			return ba.toByteArray();
		} catch (IOException ex) {
			Logger.getLogger(RPM.class.getName()).log(Level.SEVERE, null, ex);
		}
		return null;
	}

	private boolean isInternalImportSymbolRel(RPMRelocation rel) {
		return rel.target.isInternal()
			&& rel.source.symb.isImportSymbol();
	}

	private boolean isInternalNonImportSymbolRel(RPMRelocation rel) {
		return rel.target.isInternal()
			&& !(rel.source.symb.isImportSymbol());
	}

	private void sortSymbolsForExport() {
		List<RPMSymbol> sorted = new ArrayList<>(symbols.size());
		for (RPMSymbol s : symbols) {
			if (!(s.isImportSymbol() || s.isExportSymbol())) {
				sorted.add(s); //internal symbols
			}
		}
		for (RPMSymbol s : symbols) {
			if (s.isExportSymbol()) {
				sorted.add(s); //internal export symbols
			}
		}
		for (RPMSymbol s : symbols) {
			if (s.isImportSymbol()) {
				sorted.add(s); //import symbols
			}
		}
		symbols.clear();
		symbols.addAll(sorted);
	}

	public static void main(String[] args) {
		DiskFile folder = new DiskFile("D:\\_REWorkspace\\CTRMapProjects\\PMC\\vfs\\data\\patches_all");
		DiskFile outfolder = new DiskFile("D:\\_REWorkspace\\CTRMapProjects\\PMC\\vfs\\data\\patches");
		for (FSFile child : folder.listFiles()) {
			if (child.getName().endsWith(".dll") || child.getName().endsWith(".rpm")) {
				System.out.println("Conv RPM " + child);
				byte[] data = child.getBytes();
				if (child.getName().endsWith(".dll")) {
					data[0] = 'R';
					data[1] = 'P';
					data[2] = 'M';
					data[3] = '0';
				}
				RPM mod = new RPM(data);
				outfolder.getChild(child.getNameWithoutExtension() + ".dll").setBytes(mod.getBytes("DLXF"));
			}
		}
	}

	/**
	 * Sets the base address of this module, but does not update the code image.
	 *
	 * @param baseAddress The new base address.
	 */
	public void setBaseAddrNoUpdateBytes(int baseAddress) {
		this.baseAddress = baseAddress;
		code.resetBase();
		code.setBase(getCodeSegmentBase());
	}

	/**
	 * Updates the code image for the current address.
	 */
	public void updateBytesForSetBaseAddr() {
		relocateBufferToAddr(baseAddress);
	}

	/**
	 * Updates the code image for a new base address.
	 *
	 * @param baseAddr The new base address.
	 */
	public void updateBytesForBaseAddr(int baseAddr) {
		relocateBufferToAddr(baseAddr);
	}

	/**
	 * Performs this RPM's external relocations using an external relocator.
	 *
	 * @param relocator The external relocator to use.
	 */
	public void doExternalRelocations(RPMExternalRelocator relocator) {
		System.out.println("Beginning external relocation. Base address: " + Integer.toHexString(baseAddress));
		for (RPMRelocation rel : relocations) {
			if (rel.target.isExternal()) {
				relocator.processExternalRelocation(this, rel);
			}
		}
	}

	private void relocateBufferToAddr(int baseAddress) {
		if (this.baseAddress != baseAddress) {
			setBaseAddrNoUpdateBytes(baseAddress);
		}
		try {
			if (code.getOffsetBase() != getCodeSegmentBase()) {
				throw new RuntimeException("Base address mismatch! code " + Long.toHexString(code.getOffsetBase()) + " should be " + getCodeSegmentBase());
			}
			System.out.println("Beginning relocation at base " + Long.toHexString(code.getOffsetBase()));
			for (RPMRelocation rel : relocations) {
				if (rel.target.isInternal()) {
					//System.out.println("rel " + rel.target.address + ", " + rel.target.module);
					code.seekUnbased(rel.target.getAddrHWordAligned());

					writeRelocationDataByType(this, rel, code);
				}
			}
		} catch (IOException ex) {
			Logger.getLogger(RPM.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Writes a relocation into a binary image.
	 *
	 * @param rpm The RPM of the relocation.
	 * @param rel The relocation to write.
	 * @param out The binary image to write into.
	 * @throws IOException
	 */
	public static void writeRelocationDataByType(RPM rpm, RPMRelocation rel, DataIOStream out) throws IOException {
		int addr = rel.source.getWritableAddress();
		boolean targetIsThumb = (addr & 1) != 0;

		//System.out.println("Apply " + rel.targetType + " @ " + Integer.toHexString(out.getPosition()) + " -> " + Integer.toHexString(addr));
		switch (rel.target.targetType) {
			case ARM_BRANCH_LINK:
				if (targetIsThumb) {
					//Use branch with xchg since the target is Thumb
					ARMAssembler.writeBLXInstruction(out, addr);
				} else {
					ARMAssembler.writeBranchInstruction(out, addr, true);
				}
				break;
			case THUMB_BRANCH_LINK:
				System.out.println("Thumb branch from " + Integer.toHexString(out.getPosition()) + " to " + Integer.toHexString(addr) + " thumb: " + targetIsThumb);
				ThumbAssembler.writeBranchLinkInstruction(out, addr, !targetIsThumb);
				break;
			case OFFSET:
				out.writeInt(addr);
				break;
			case ARM_BRANCH:
				if (!targetIsThumb) {
					ARMAssembler.writeBranchInstruction(out, addr, false);
				} else {
					//TODO SOLVE STACK STUFF FOR ARM TOO
					ARMAssembler.writeBlockDataTransferInstruction(out, true, false, false, true, false, ARMAssembler.ARM_STACKPTR_REG_NUM, ARMAssembler.ARMCondition.AL, ARMAssembler.ARM_LINK_REG_NUM);
					ARMAssembler.writeBLXInstruction(out, addr);
					ARMAssembler.writeBlockDataTransferInstruction(out, false, true, false, true, true, ARMAssembler.ARM_STACKPTR_REG_NUM, ARMAssembler.ARMCondition.AL, ARMAssembler.ARM_PRGCNT_REG_NUM);
				}
				break;
			case THUMB_BRANCH:
				if (Math.abs((out.getPosition() + 4) - addr) < 2048) {
					ThumbAssembler.writeSmallBranchInstruction(out, addr);
				} else {
					//BUGFIX: DO NOT USE R0. It will overwrite function arguments.
					//ARM call standard uses registers R0,R1,R2,R3 for args
					//Using R4+ could potentially break caller functions that use them
					//(spoiler alert: it will)
					//The only option I think we are left with is to just do a branch with link
					//The size will be the same since BL does not require the extra 4 bytes of address

					/*int tgtAddr = ThumbAssembler.writePcRelativeLoadAbs(out, 4, out.getPosition() + 4);
					ThumbAssembler.writeBXInstruction(out, 4);
					System.out.println("load rsl " + Integer.toHexString(tgtAddr));
					out.seek(tgtAddr);
					out.writeInt(addr);*/
					//Potentially destructive if the address does not fit under 4MB
					//-> Unsuitable for 3DS, but a non-issue since ARM encoding is used there in most cases
					ThumbAssembler.writePushPopInstruction(out, false, true);
					ThumbAssembler.writeBranchLinkInstruction(out, addr, !targetIsThumb);
					ThumbAssembler.writePushPopInstruction(out, true, true);
					out.writeInt(addr);
				}
				break;
			case THUMB_BRANCH_SAFESTACK:
				ThumbAssembler.writeHiMovInstruction(out, 11, 4);
				int loadDest = ThumbAssembler.writePcRelativeLoad(out, 4, 8);
				ThumbAssembler.writeHiMovInstruction(out, 12, 4);
				ThumbAssembler.writeHiMovInstruction(out, 4, 11);
				ThumbAssembler.writeBXInstruction(out, 12);
				out.seek(loadDest);
				out.writeInt(addr);
				break;
			case OFFSET_REL31:
				int highbits = out.readInt() & 0x80000000;
				out.skipBytes(-4);
				out.writeInt(((addr - out.getPosition()) & 0x7FFFFFFF) | highbits);
				break;
			case FULL_COPY:
				int copyStartAdr = rel.source.getAddress();
				int copyEndAdr;

				int len = rel.source.getLength();
				if (len > 0) {
					copyEndAdr = copyStartAdr + len;
					rpm.code.seekUnbased(copyStartAdr);
					byte[] bytes = new byte[len];
					rpm.code.read(bytes);

					int pos = out.getPosition();

					out.write(bytes);

					System.out.println("FULL_COPIED to " + Integer.toHexString(pos) + " (size 0x" + Integer.toHexString(bytes.length) + " bytes)");
					for (RPMRelocation copyRel : rpm.relocations) {
						if (copyRel.target.isInternal() && copyRel.target.targetType != RPMRelTargetType.FULL_COPY) {
							int copyRelAddr = copyRel.target.getAddrHWordAligned();
							if (copyRelAddr >= copyStartAdr && copyRelAddr < copyEndAdr) {
								out.seek(pos + (copyRelAddr - copyStartAdr));
								System.out.println("Applying mirrored relocation at " + Integer.toHexString(out.getPosition()) + " type " + copyRel.target.targetType);
								writeRelocationDataByType(rpm, copyRel, out);
							}
						}
					}
				} else {
					throw new UnsupportedOperationException("Can not FULL_COPY a symbol without a length! - " + rel.source.symb.name);
				}
				break;
		}
	}

	/**
	 * Gets a symbol from this RPM by its name.
	 *
	 * @param symbName Name of the symbol.
	 * @return
	 */
	public RPMSymbol getSymbol(String symbName) {
		for (RPMSymbol s : symbols) {
			if (Objects.equals(s.name, symbName)) {
				return s;
			}
		}
		return null;
	}

	/**
	 * Gets a symbol from this RPM by its number.
	 *
	 * @param symbNo Index of the symbol.
	 * @return
	 */
	public RPMSymbol getSymbol(int symbNo) {
		if (symbNo < symbols.size() && symbNo >= 0) {
			return symbols.get(symbNo);
		}
		return null;
	}

	/**
	 * Finds a global (non-relocatable) symbol in the RPM by its address.
	 *
	 * @param addr Address of the global symbol.
	 * @return
	 */
	public RPMSymbol findGlobalSymbolByAddrAbs(int addr) {
		for (RPMSymbol s : symbols) {
			if (s.isGlobal()) {
				if (s.address.getAddrAbs() == addr) {
					return s;
				}
			}
		}
		return null;
	}

	/**
	 * Gets the number of a symbol in this RPM.
	 *
	 * @param symb The symbol.
	 * @return Index of 'symb'.
	 */
	public int getSymbolNo(RPMSymbol symb) {
		return symbols.indexOf(symb);
	}

	/**
	 * Gets an external symbol using an attached external symbol resolver.
	 *
	 * @param namespace The named segment of the symbol.
	 * @param name Name of the symbol.
	 * @return
	 */
	public RPMSymbol getExternalSymbol(String namespace, String name) {
		if (extResolver == null) {
			return null;
		}
		return extResolver.resolveExSymbol(namespace, name);
	}
}
