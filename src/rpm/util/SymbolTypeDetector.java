package rpm.util;

public class SymbolTypeDetector {

	public static boolean isMostLikelyValueName(String str) {
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
}
