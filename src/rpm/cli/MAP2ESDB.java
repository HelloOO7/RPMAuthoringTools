package rpm.cli;

import rpm.elfconv.ESDBAddress;
import rpm.elfconv.ESDBSegmentInfo;
import rpm.elfconv.ExternalSymbolDB;
import ctrmap.stdlib.cli.ArgumentBuilder;
import ctrmap.stdlib.cli.ArgumentContent;
import ctrmap.stdlib.cli.ArgumentPattern;
import ctrmap.stdlib.cli.ArgumentType;
import ctrmap.stdlib.formats.yaml.Yaml;
import ctrmap.stdlib.formats.yaml.YamlNode;
import ctrmap.stdlib.fs.accessors.DiskFile;
import ctrmap.stdlib.text.FormattingUtils;
import ctrmap.stdlib.text.StringEx;
import ctrmap.stdlib.util.JVMClassSourceChecker;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
public class MAP2ESDB {

	private static final ArgumentPattern[] ARG_PTNS = new ArgumentPattern[]{
		new ArgumentPattern("input", "An input MAP file with function/field names and addresses.", ArgumentType.STRING, null, "-i", "--map", "--input"),
		new ArgumentPattern("thmfile", "A file specifying the T segment register value ranges.", ArgumentType.STRING, null, "-t", "--thmfile"),
		new ArgumentPattern("aliases", "A file specifying alternative symbol names.", ArgumentType.STRING, null, "-a", "--alias"),
		new ArgumentPattern("merge", "Additional input ESDB files to merge.", ArgumentType.STRING, null, true, "-m", "--merge"),
		new ArgumentPattern("output", "An output YML file.", ArgumentType.STRING, "esdb.yml", "-o", "--yml", "--output"),
		new ArgumentPattern("help", "Prints the help dialog.", ArgumentType.BOOLEAN, false, "-h", "--help", "-?")
	};

	public static void main(String[] args) {
		if (!JVMClassSourceChecker.isJAR() && args.length == 0) {
			args = new String[]{
				"-i \"D:\\_REWorkspace\\pokescript_genv\\codeinjection_new\\arm9-white2_decompressed_U.map\"",
				"-t \"D:\\_REWorkspace\\pokescript_genv\\codeinjection_new\\arm9-white2_decompressed_U.thm\"",
				"-a \"D:\\_REWorkspace\\pokescript_genv\\codeinjection_new\\arm9-white2_decompressed_U.alias\"",
				"-m \"D:\\_REWorkspace\\pokescript_genv\\codeinjection_new\\esdb_293.yml\"",
				"-o \"D:\\_REWorkspace\\pokescript_genv\\codeinjection_new\\esdb.yml\""
			};
		}
		ArgumentBuilder bld = new ArgumentBuilder(ARG_PTNS);
		bld.parse(args);

		boolean printHelp = bld.getContent("help").booleanValue();

		if (printHelp) {
			printHelp(bld);
		}

		if (bld.hasArgument("input")) {
			File in = getFileNonNull(bld, "input");
			File thm = getFileNonNull(bld, "thmfile");
			File alias = getFileNonNull(bld, "aliases");
			if (in != null && thm == null) {
				throw new RuntimeException("MAP file specified without a THM file!");
			}
			File out = new File(bld.getContent("output").stringValue());
			System.out.println("Converting MAP file " + in + " into " + out);

			File[] merge = new File[0];

			ArgumentContent mergeCnt = bld.getContent("merge", true);
			if (mergeCnt != null) {
				merge = new File[mergeCnt.valueCount()];
				for (int i = 0; i < merge.length; i++) {
					merge[i] = new File(mergeCnt.stringValue(i));
				}
			}

			createYML(in, thm, alias, out, merge);
		} else {
			if (!printHelp) {
				printHelp(bld);
				System.out.println();
				System.out.println("No input file specified.");
			}
		}
	}

	private static File getFileNonNull(ArgumentBuilder bld, String tag) {
		ArgumentContent cnt = bld.getContent(tag, true);
		if (cnt != null) {
			return new File(cnt.stringValue());
		}
		return null;
	}

	private static void printHelp(ArgumentBuilder bld) {
		System.out.println("MAP2ESDB External symbol database converter 1.0.0\n");

		bld.print();
	}

	public static void createYML(File mapFile, File thmFile, File aliasFile, File ymlFile, File... mergeFiles) {
		try {
			ExternalSymbolDB esdb = new ExternalSymbolDB();

			if (mapFile != null && thmFile != null) {
				Scanner srScanner = new Scanner(thmFile);

				List<SegmentRegisterInfo> segRegs = new ArrayList<>();

				while (srScanner.hasNextLine()) {
					String line = srScanner.nextLine().trim();
					if (!line.isEmpty()) {
						segRegs.add(new SegmentRegisterInfo(line));
					}
				}

				srScanner.close();

				Scanner s = new Scanner(mapFile);

				List<Integer> segments = new ArrayList<>();

				boolean readAddresses = false;
				boolean hasSegments = false;

				List<MapSegmentInfo> segmentInfo = new ArrayList<>();

				while (s.hasNextLine()) {
					String line = s.nextLine();
					if (readAddresses) {
						int iddd = line.indexOf(":");
						if (iddd != -1) {
							int idWs = StringEx.indexOfFirstWhitespace(line, iddd + 1);
							if (idWs != -1) {
								int seg = Integer.parseUnsignedInt(line.substring(0, iddd).trim(), 16);
								if (!segments.contains(seg)) {
									segments.add(seg);
								}
								int addr = Integer.parseUnsignedInt(line.substring(iddd + 1, idWs).trim(), 16);

								String name = line.substring(idWs).trim();
								esdb.putOffset(name, new ESDBAddress(seg, addr));
							}
						}
					} else {
						int iddd = line.indexOf(":");
						if (iddd != -1) {
							hasSegments = true;

							int idWs = StringEx.indexOfFirstWhitespace(line, iddd + 1);
							if (idWs != -1) {
								int seg = Integer.parseUnsignedInt(line.substring(0, iddd).trim(), 16);
								int addr = Integer.parseUnsignedInt(line.substring(iddd + 1, idWs).trim(), 16);

								int lenStart = StringEx.indexOfFirstNonWhitespace(line, idWs);
								int lenEnd = StringEx.indexOfFirstWhitespace(line, lenStart);
								int len = Integer.parseUnsignedInt(line.substring(lenStart, lenEnd).replace("H", ""), 16);

								int nameStart = StringEx.indexOfFirstNonWhitespace(line, lenEnd);
								int nameEnd = StringEx.indexOfFirstWhitespace(line, nameStart);
								String name = line.substring(nameStart, nameEnd);

								MapSegmentInfo si = new MapSegmentInfo(seg, name, addr, len);
								segmentInfo.add(si);
							}
						}

						readAddresses = line.contains("Address");
					}
				}

				List<ESDBAddress> addrQueue = new ArrayList<>(esdb.getAddresses());

				for (SegmentRegisterInfo sri : segRegs) {
					for (int i = 0; i < addrQueue.size(); i++) {
						ESDBAddress a = addrQueue.get(i);
						if (a.address >= sri.startAddr && a.address < sri.endAddr && (a.address & 1) == 0) {
							String name = esdb.getNameOfAddress(a);
							if (!isMostLikelyValueName(name)) {
								a.address += sri.value;
							} else {
								System.out.println("Guessing symbol " + name + " to be a value. Omitting Thumb bit.");
							}
							addrQueue.remove(i);
							i--;
						}
					}
					if (addrQueue.isEmpty()) {
						break;
					}
				}

				if (!hasSegments) {
					for (Integer seg : segments) {
						ESDBSegmentInfo si = new ESDBSegmentInfo();
						si.segmentId = seg;
						si.segmentName = "SEG_" + FormattingUtils.getStrWithLeadingZeros(4, Integer.toHexString(seg).toUpperCase());
						si.segmentType = null;
						esdb.putSegment(si);
					}
				} else {
					List<Integer> takenIDs = new ArrayList<>();
					for (MapSegmentInfo si : segmentInfo) {
						if (!takenIDs.contains(si.id)) {
							takenIDs.add(si.id);
						}
					}
					List<Integer> usedIDs = new ArrayList<>();
					for (MapSegmentInfo si : segmentInfo) {
						ESDBSegmentInfo esi = new ESDBSegmentInfo();
						int sid = si.id;
						if (usedIDs.contains(sid)) {
							while (usedIDs.contains(sid)) {
								sid++;
								while (takenIDs.contains(sid)) {
									sid++;
								}
							}
						}
						usedIDs.add(sid);
						si.id = sid;
						esi.segmentId = sid;
						if (si.name.contains("OVL")) {
							esi.segmentType = "OVERLAY";
						} else if (si.name.equals("ARM9")) {
							esi.segmentType = "EXECUTABLE";
						}
						esi.segmentName = StringEx.deleteAllString(si.name, "OVL_");
						esdb.putSegment(esi);
					}

					for (ESDBAddress a : esdb.getAddresses()) {
						a.segment = getSegmentOfAddress(a.address, segmentInfo).id;
					}
				}
			}

			for (File merge : mergeFiles) {
				ExternalSymbolDB esdbMerge = new ExternalSymbolDB(new DiskFile(merge));

				Map<Integer, Integer> mergeSegIdRemap = new HashMap<>();

				for (ESDBSegmentInfo s : esdbMerge.getSegments()) {
					if (esdb.getSegById(s.segmentId) != null) {
						int nextId = esdb.getNextSegID();
						mergeSegIdRemap.put(s.segmentId, nextId);
						s.segmentId = nextId;
					} else {
						mergeSegIdRemap.put(s.segmentId, s.segmentId);
					}
					esdb.putSegment(s);
				}

				for (ESDBAddress mergeAddr : esdbMerge.getAddresses()) {
					String name = esdbMerge.getNameOfAddress(mergeAddr);
					esdb.putOffset(name, new ESDBAddress(mergeSegIdRemap.get(mergeAddr.segment), mergeAddr.address));
				}
			}

			if (aliasFile != null) {
				Yaml aliasYml = new Yaml(new DiskFile(aliasFile));
				for (YamlNode mapping : aliasYml.root.children) {
					String alias = mapping.getKey();
					String target = mapping.getValue();
					esdb.putAlias(alias, target);
				}
			}

			esdb.writeToFile(new DiskFile(ymlFile));
		} catch (FileNotFoundException ex) {
			Logger.getLogger(MAP2ESDB.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	private static boolean isMostLikelyValueName(String str) {
		if (str.startsWith("g_")) {
			return true;
		}
		int capitalCount = 0;
		int lowercaseCount = 0;
		boolean isAnyUpperCase = false;
		boolean isLastLowercase = false;
		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			if (Character.isLetter(c)) {
				if (Character.isUpperCase(c)) {
					capitalCount++;
					isLastLowercase = false;
					isAnyUpperCase = true;
				} else {
					if (isLastLowercase && (i > 2 || isAnyUpperCase)) {
						//permit ppTHING, but break otherwise
						return false;
					}
					if (c != 'p' && c != 's' && c != 'g') {
						return false;
					}
					lowercaseCount++;
					isLastLowercase = true;
				}
			}
		}
		//naming conventions should only allow lowercase for stuff like ppTHING or ppSTUFF_IDs
		return capitalCount > lowercaseCount && lowercaseCount <= 3;
	}

	private static MapSegmentInfo getSegmentOfAddress(int add, List<MapSegmentInfo> l) {
		for (MapSegmentInfo si : l) {
			if (add >= si.start && add < si.end) {
				return si;
			}
		}
		throw new RuntimeException("no segment for address " + Integer.toHexString(add));
	}

	public static class SegmentRegisterInfo {

		public int value;

		public int startAddr;
		public int endAddr;
		public int length;

		public SegmentRegisterInfo(String line) {
			String[] parts = line.split("\\s+");
			startAddr = Integer.parseUnsignedInt(parts[0], 16);
			endAddr = Integer.parseUnsignedInt(parts[1], 16);
			length = Integer.parseUnsignedInt(parts[2], 16);
			value = Integer.parseUnsignedInt(parts[3], 16);
		}
	}

	public static class MapSegmentInfo {

		public int id;
		public int start;
		public int end;
		public int length;
		public String name;

		public MapSegmentInfo(int id, String name, int start, int length) {
			this.id = id;
			this.start = start;
			this.length = length;
			this.end = start + length;
			this.name = name;
		}
	}
}
