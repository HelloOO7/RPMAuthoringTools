package rpm.format.rpz;

import xstandard.formats.yaml.YamlNodeName;

public class RPZVersion {

	/**
	 * An arbitrary name of the version.
	 */
	@YamlNodeName("Name")
	public String name;
	/**
	 * A non-negative version number. Higher versions must use higher numbers.
	 */
	@YamlNodeName("Number")
	public int number;
}
