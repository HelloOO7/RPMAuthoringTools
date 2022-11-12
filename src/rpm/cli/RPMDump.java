package rpm.cli;

import xstandard.cli.ArgumentBuilder;
import xstandard.cli.ArgumentContent;
import xstandard.cli.ArgumentPattern;
import xstandard.cli.ArgumentType;
import rpm.format.rpm.RPM;
import rpm.format.rpm.RPMMetaData;
import rpm.format.rpm.RPMRelocation;
import rpm.format.rpm.RPMSymbol;
import xstandard.fs.FSFile;
import xstandard.fs.accessors.DiskFile;
import xstandard.io.base.iface.IOStream;
import xstandard.io.util.IndentedPrintStream;
import xstandard.util.JVMClassSourceChecker;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RPMDump {

	private static final ArgumentPattern[] ARG_PATTERNS = new ArgumentPattern[]{
		new ArgumentPattern("input", "RPM file to dump", ArgumentType.STRING, null, "-i", "--input"),
		new ArgumentPattern("output", "File to dump into (leave blank for console output)", ArgumentType.STRING, null, "-o", "--output"),
		new ArgumentPattern("fourcc", "Override the recognized RPM format signature.", ArgumentType.STRING, RPM.RPM_PROLOG_MAGIC, "--fourcc"),
		new ArgumentPattern("dumprel", "Dump relocations", ArgumentType.BOOLEAN, true, "-r"),
		new ArgumentPattern("dumpsym", "Dump symbols", ArgumentType.BOOLEAN, true, "-s"),
		new ArgumentPattern("dumpmeta", "Dump metadata", ArgumentType.BOOLEAN, true, "-m"),
		new ArgumentPattern("help", "Prints the help dialog.", ArgumentType.BOOLEAN, false, "-h", "--help", "-?")
	};

	public static void main(String[] args) {
		if (!JVMClassSourceChecker.isJAR() && args.length == 0) {
			args = new String[]{
				"--fourcc RPM0",
				"D:\\_REWorkspace\\pokescript_genv\\codeinjection_new\\PMC\\build\\PMC.rpm",
				"-r -s -m"
			};
		}
		ArgumentBuilder bld = new ArgumentBuilder(ARG_PATTERNS);
		bld.parse(args);

		boolean printHelp = bld.getContent("help").booleanValue();

		if (printHelp) {
			printHelp(bld);
		}

		if (bld.hasArgument("input") || bld.defaultContent.exists()) {
			String input = bld.defaultContent.exists() ? bld.defaultContent.stringValue() : bld.getContent("input").stringValue();
			FSFile inFile = new DiskFile(input);
			if (inFile.exists()) {
				try {
					String fourCC = bld.getContent("fourcc").stringValue();

					IOStream in = inFile.getIO();
					RPM rpm = new RPM(in, fourCC, 0, -1);
					in.close();

					ArgumentContent output = bld.getContent("output", true);

					OutputStream outstrm;
					if (output != null) {
						FSFile outFile = new DiskFile(output.stringValue());
						if (!outFile.canWrite()) {
							System.err.println("Output file " + output.stringValue() + " can not be written to!");
							return;
						}
						outstrm = outFile.getNativeOutputStream();
					} else {
						outstrm = System.out;
					}

					IndentedPrintStream out = new IndentedPrintStream(outstrm);

					out.println(fourCC + " file | Code segment base address: 0x" + Integer.toHexString(rpm.getCodeSegmentBase()) + " | BSS size: 0x" + Integer.toHexString(rpm.bssSize));
					out.println("Symbol count: " + rpm.symbols.size() + " | Relocation count: " + rpm.relocations.size());

					if (bld.getContent("dumpsym").booleanValue()) {
						dumpSymbolTable(out, rpm);
					}

					if (bld.getContent("dumprel").booleanValue()) {
						dumpRelocationTable(out, rpm);
					}

					if (bld.getContent("dumpmeta").booleanValue()) {
						dumpMetaData(out, rpm);
					}
					out.close();
				} catch (IOException ex) {
					Logger.getLogger(RPMDump.class.getName()).log(Level.SEVERE, null, ex);
				}
			} else {
				System.out.println("Input file " + input + " does not exist!");
			}
		} else {
			if (!printHelp) {
				printHelp(bld);
				System.out.println();
				System.out.println("No input file specified!");
			}
		}
	}

	private static void printHelp(ArgumentBuilder bld) {
		System.out.println("RPM Dump Utility 1.0.0");
		System.out.println();
		bld.print();
	}

	private static void dumpSymbolTable(IndentedPrintStream out, RPM rpm) {
		out.println();
		out.println("Symbol table:");
		out.incrementIndentLevel();

		int symIndex = 0;
		for (RPMSymbol sym : rpm.symbols) {
			out.println("Symbol " + symIndex + " | " + 
				(sym.name == null 
					? (sym.nameHash == 0 ? "<anonymous>" : "Hash: " + Integer.toHexString(sym.nameHash)) 
					: sym.name + " (hash: " + Integer.toHexString(sym.nameHash) + ")"
				)
			);
			out.incrementIndentLevel();
			out.println("Address: " + Integer.toHexString(sym.address));
			out.println("Type: " + sym.type);
			out.println("Size: " + sym.size);
			out.print("Attributes: [");

			int[] ATTRS = new int[]{RPMSymbol.RPM_SYMATTR_EXPORT, RPMSymbol.RPM_SYMATTR_IMPORT, RPMSymbol.RPM_SYMATTR_GLOBAL};
			String[] ATTRS_NAMES = new String[]{"Export symbol", "Import symbol", "Global symbol"};

			boolean added = false;
			for (int i = 0; i < ATTRS.length; i++) {
				if ((sym.attributes & ATTRS[i]) != 0) {
					if (added) {
						out.print(", ");
					}
					out.print(ATTRS_NAMES[i]);
					added = true;
				}
			}

			out.println("]");
			out.decrementIndentLevel();
			symIndex++;
		}

		out.decrementIndentLevel();
	}

	private static void dumpRelocationTable(IndentedPrintStream out, RPM rpm) {
		out.println();
		out.println("Relocation table:");

		out.incrementIndentLevel();

		int relIndex = 0;
		for (RPMRelocation rel : rpm.relocations) {
			out.println("Relocation " + relIndex);
			out.incrementIndentLevel();
			out.println("Source symbol: " + rel.source.symb);
			out.println("Target: " + rel.target.targetType + " @ " + rel.target.module + " :: 0x" + Integer.toHexString(rel.target.address));
			out.decrementIndentLevel();
			relIndex++;
		}

		out.decrementIndentLevel();
	}

	private static void dumpMetaData(IndentedPrintStream out, RPM rpm) {
		out.println();
		out.println("Metadata:");
		out.incrementIndentLevel();
		for (RPMMetaData.RPMMetaValue mv : rpm.metaData) {
			out.println(mv.name + ":");
			out.incrementIndentLevel();
			out.println("Type: " + mv.type);
			out.println("Value: " + mv.stringValue());
			out.decrementIndentLevel();
		}
		out.decrementIndentLevel();
	}
}
