package rpm.cli;

import rpm.elfconv.ELF2RPM;
import rpm.elfconv.ExternalSymbolDB;
import ctrmap.stdlib.cli.ArgumentBuilder;
import ctrmap.stdlib.cli.ArgumentContent;
import ctrmap.stdlib.cli.ArgumentPattern;
import ctrmap.stdlib.cli.ArgumentType;
import ctrmap.stdlib.formats.yaml.Yaml;
import ctrmap.stdlib.formats.yaml.YamlListElement;
import ctrmap.stdlib.formats.yaml.YamlNode;
import rpm.format.rpm.RPM;
import ctrmap.stdlib.fs.FSFile;
import ctrmap.stdlib.fs.FSUtil;
import ctrmap.stdlib.fs.accessors.DiskFile;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import rpm.format.rpm.RPMRelTargetType;
import rpm.format.rpm.RPMRelocation;
import rpm.format.rpm.RPMRelocationSource;
import rpm.format.rpm.RPMRelocationTarget;
import rpm.format.rpm.RPMSymbol;
import rpm.format.rpm.RPMSymbolAddress;
import rpm.format.rpm.RPMSymbolType;
import rpm.util.AutoRelGenerator;

public class RPMTool {

	private static final ArgumentPattern[] ARG_PTNS = new ArgumentPattern[]{
		new ArgumentPattern("input", "Input files (can be ELF or RPM).", ArgumentType.STRING, null, true, "-i", "--input"),
		new ArgumentPattern("output", "The output RPM file.", ArgumentType.STRING, null, "-o", "--output"),
		new ArgumentPattern("esdb", "An external symbol database for ELF conversion.", ArgumentType.STRING, null, "--esdb"),
		new ArgumentPattern("mapfile", "A MAP file to export RPM symbols into.", ArgumentType.STRING, null, "--mapfile"),
		new ArgumentPattern("inrelfile", "An YML file to import external relocations from.", ArgumentType.STRING, null, "--in-relocations-yml"),
		new ArgumentPattern("outrelfile", "An YML file to export external relocations into.", ArgumentType.STRING, null, "--out-relocations-yml"),
		new ArgumentPattern("genreloc", "Generate relocation data from special function names.", ArgumentType.BOOLEAN, false, "--generate-relocations"),
		new ArgumentPattern("strip", "Strip all symbol name strings.", ArgumentType.BOOLEAN, false, "--strip-symbol-names")
	};

	public static void main(String[] args) {
		ArgumentBuilder bld = new ArgumentBuilder(ARG_PTNS);
		bld.parse(args);

		try {
			ArgumentContent input = bld.getContent("input", true);
			if (input == null) {
				input = bld.defaultContent;
			}
			if (input != null && input.exists()) {
				String inputPath = input.stringValue();
				ArgumentContent output = bld.getContent("output", true);
				ArgumentContent map = bld.getContent("mapfile", true);
				String outPath;
				if (output == null) {
					outPath = FSUtil.getFilePathWithoutExtension(inputPath) + RPM.EXTENSION_FILTER.getPrimaryExtension();
				} else {
					outPath = output.stringValue();
				}

				ExternalSymbolDB esdb = null;
				if (bld.getContent("esdb", true) != null) {
					String esdbPath = bld.getContent("esdb").stringValue();
					esdb = new ExternalSymbolDB(new DiskFile(esdbPath));
				}

				RPM rpm = new RPM();
				boolean isElf = false;

				for (int i = 0; i < input.contents.size(); i++) {
					DiskFile inDf = new DiskFile(input.stringValue(i));

					if (RPM.isRPM(inDf)) {
						rpm.merge(new RPM(inDf));
					} else {
						isElf = true;
						rpm.merge(ELF2RPM.getRPM(new DiskFile(input.stringValue(i)), esdb == null ? new ExternalSymbolDB() : esdb));
					}
				}

				if (bld.getContent("genreloc").booleanValue()) {
					if (esdb != null) {
						AutoRelGenerator.makeHooksAuto(rpm, esdb);
					} else {
						System.out.println("Can not generate external relocations without ESDB!");
					}
				}
				if (bld.getContent("strip").booleanValue()) {
					rpm.stripSymbolNames();
					System.out.println("Stripped symbol names.");
				}
				if (bld.hasArgument("inrelfile")) {
					FSFile inrelfile = new DiskFile(bld.getContent("inrelfile").stringValue());
					if (inrelfile.isFile()) {
						System.out.println("Could not read external relocation YML!");
					}
					else {
						readRelocationsFromYml(rpm, inrelfile);
					}
				}
				if (bld.hasArgument("outrelfile")) {
					FSFile outrelfile = new DiskFile(bld.getContent("outrelfile").stringValue());
					if (!outrelfile.isDirectory() && outrelfile.canWrite()) {
						writeRelocationsAsYml(rpm, outrelfile);
					}
					else {
						System.out.println("Can not write to external relocation YML!");
					}
				}

				if (map != null) {
					FSFile mapFile = new DiskFile(map.stringValue());
					rpm.writeMAPToFile(mapFile);
				}

				if (isElf || input.contents.size() > 1) {
					FSUtil.writeBytesToFile(new File(outPath), rpm.getBytes());
				}
			} else {
				System.out.println("No input given.\n");
				printHelp(bld);
			}
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			System.out.println();
			printHelp(bld);
		}
	}

	private static void printHelp(ArgumentBuilder bld) {
		System.out.println("RPMTool 1.0.0\n");
		bld.print();
	}

	public static void readRelocationsFromYml(RPM destRPM, FSFile sourceFile) {
		List<RPMRelocation> relocations = new ArrayList<>();

		Yaml yml = new Yaml(sourceFile);

		YamlNode relNode = yml.getRootNodeKeyNode("Relocations");

		for (YamlNode rel : relNode.children) {
			RPMRelocation out = new RPMRelocation();

			RPMSymbol s;

			if (rel.hasChildren("SourceAddress")) {
				int srcAddr = rel.getChildIntValue("SourceAddress");
				s = destRPM.findGlobalSymbolByAddrAbs(srcAddr);
				if (s == null) {
					s = new RPMSymbol(destRPM, "SYM_" + Integer.toHexString(srcAddr), RPMSymbolType.VALUE, new RPMSymbolAddress(destRPM, RPMSymbolAddress.RPMAddrType.GLOBAL, srcAddr));
					destRPM.symbols.add(s);
				}
			} else {
				String symName = rel.getChildByName("SourceSymbol").getValue();
				s = destRPM.getSymbol(symName);
			}
			if (s != null) {
				out.source = new RPMRelocationSource(destRPM, s);
				RPMRelTargetType tt = RPMRelTargetType.fromName(rel.getChildByName("TargetType").getValue());
				if (tt != null) {
					out.target = new RPMRelocationTarget(rel.getChildByName("TargetAddress").getValueInt(), rel.getChildByName("TargetSegment").getValue(), tt);
					relocations.add(out);
				}
			}
		}
		destRPM.setExternalRelocations(relocations);
	}

	public static void writeRelocationsAsYml(RPM srcRpm, FSFile targetFile) {
		Yaml yml = new Yaml();

		YamlNode hooksNode = yml.getEnsureRootNodeKeyNode("Relocations");

		for (RPMRelocation hk : srcRpm.getExternalRelocations()) {
			YamlNode n = new YamlNode(new YamlListElement());
			n.addChild("TargetType", hk.target.targetType);
			n.addChild("TargetSegment", hk.target.module);
			n.addChild("TargetAddress", hk.target.address, true);
			n.addChild("SourceSymbol", hk.source.symb.name);
			hooksNode.addChild(n);
		}

		yml.writeToFile(targetFile);
	}
}
