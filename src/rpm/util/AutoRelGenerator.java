package rpm.util;

import rpm.elfconv.ESDBSegmentInfo;
import rpm.elfconv.ExternalSymbolDB;
import rpm.format.rpm.RPM;
import rpm.format.rpm.RPMRelTargetType;
import rpm.format.rpm.RPMRelocation;
import rpm.format.rpm.RPMRelocationSource;
import rpm.format.rpm.RPMRelocationTarget;
import rpm.format.rpm.RPMSymbol;
import xstandard.math.MathEx;
import xstandard.text.StringEx;
import xstandard.util.ArraysEx;
import xstandard.util.ParsingUtils;
import java.util.List;

public class AutoRelGenerator {

	public static void makeHooksAuto(RPM rpm, ExternalSymbolDB esdb) {
		for (RPMSymbol smb : rpm.symbols) {
			if (smb.name != null) {
				List<RPMRelTargetType> sortedTT = ArraysEx.asList(RPMRelTargetType.values());

				sortedTT.sort((o1, o2) -> {
					return o2.name().length() - o1.name().length();
				});

				for (RPMRelTargetType tt : sortedTT) {
					if (smb.name.startsWith(tt.name())) {
						String nameOfHookedFunc = smb.name.substring(tt.name().length() + 1);

						String[] possibleAddr = StringEx.splitOnecharFastNoBlank(nameOfHookedFunc, '_');

						int hookedAddr = -1;
						int funcHookAddend = 0;
						String segmentName = null;

						if (possibleAddr.length >= 2) {
							int addrIdx = possibleAddr.length - 1;
							for (; addrIdx >= 0; addrIdx--) {
								hookedAddr = ParsingUtils.parseBasedIntOrDefault(possibleAddr[addrIdx], -1);
								if (hookedAddr != -1) {
									break;
								}
							}
							if (hookedAddr != -1) {
								String fullName = StringEx.join('_', 0, addrIdx, possibleAddr);
								if (esdb.isFuncExternal(fullName)) {
									nameOfHookedFunc = fullName;
									funcHookAddend = hookedAddr;
									hookedAddr = -1;
								} else {
									segmentName = possibleAddr[addrIdx - 1];
								}
							}
						}

						if (hookedAddr == -1 && esdb.isFuncExternal(nameOfHookedFunc)) {
							ESDBSegmentInfo seg = esdb.getSegOfFunc(nameOfHookedFunc);
							NTRSegmentType segType = NTRSegmentType.fromName(seg.segmentType);
							if (segType != null) {
								hookedAddr = esdb.getOffsetOfFunc(nameOfHookedFunc);
								if (funcHookAddend != 0) {
									hookedAddr += funcHookAddend;
									hookedAddr = MathEx.padIntegerDownPow2(hookedAddr, 1);
								}
								segmentName = seg.segmentName;
							}
						}

						if (hookedAddr != -1 && rpm.findExternalRelocationByAddress(segmentName, hookedAddr) == null) {
							RPMRelocation rel = new RPMRelocation();
							rel.source = new RPMRelocationSource(rpm, smb);
							rel.target = new RPMRelocationTarget(hookedAddr, segmentName, tt);
							rpm.relocations.add(rel);
							System.out.println("Created automated hook of type " + rel.target.targetType + " at " + smb.name + " to " + segmentName + ":" + Integer.toHexString(hookedAddr));
						} else {
							System.out.println("Warning: Could not create automated hook at " + smb.name);
							if (hookedAddr == -1) {
								if (segmentName == null) {
									System.out.println("Reason: Could not resolve segment.");
								}
							} else {
								System.out.println("Reason: External relocation at this address already exists.");
							}
						}
						break;
					}
				}
			}
		}
	}

	public static enum NTRSegmentType {
		EXECUTABLE,
		OVERLAY;

		public static NTRSegmentType fromName(String name) {
			if (name == null) {
				return null;
			}
			String query = name.toLowerCase();
			for (NTRSegmentType s : values()) {
				if (s.name().toLowerCase().equals(query)) {
					return s;
				}
			}
			return null;
		}
	}
}
