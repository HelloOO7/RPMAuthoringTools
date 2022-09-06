package rpm.format.rpz;

import xstandard.formats.yaml.Yaml;
import xstandard.formats.yaml.YamlNodeName;
import xstandard.formats.yaml.YamlReflectUtil;
import xstandard.fs.FSFile;

class RPZYmlBase extends Yaml {

	@YamlNodeName("Name")
	public String name;
	@YamlNodeName("Version")
	public RPZVersion version;

	public RPZYmlBase(FSFile fsf) {
		super(fsf);
		YamlReflectUtil.deserializeToObject(root, this);
	}
	
	public RPZYmlBase(){
		super();
		version = new RPZVersion();
	}

	@Override
	public void write() {
		root.removeAllChildren();
		YamlReflectUtil.addFieldsToNode(root, this);
		super.write();
	}


	public static class RPZYmlReference {
		@YamlNodeName("YmlPath")
		public String ymlPath;
	}
}
