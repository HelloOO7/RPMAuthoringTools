package rpm.elfconv;

import xstandard.formats.yaml.YamlListElement;
import xstandard.formats.yaml.YamlNode;

public class ESDBSegmentInfo {
	public int segmentId;
	public String segmentName;
	public String segmentType;
	
	public ESDBSegmentInfo(YamlNode node){
		segmentId = node.getChildByName("ID").getValueInt();
		segmentName = node.getChildByName("Name").getValue();
		segmentType = node.getChildByName("Type").getValue();
	}
	
	public ESDBSegmentInfo(){
		
	}
	
	public ESDBSegmentInfo(int id, String name, String type) {
		this.segmentId = id;
		this.segmentName = name;
		this.segmentType = type;
	}
	
	public YamlNode getNode(){
		YamlNode n = new YamlNode(new YamlListElement());
		n.addChild("ID", segmentId, true);
		n.addChild("Name", segmentName);
		n.addChild("Type", segmentType);
		return n;
	}
}
