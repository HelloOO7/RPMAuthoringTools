package rpm.format.rpm;

import ctrmap.stdlib.io.structs.StringTable;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Container for user-defined metadata inside RPM binaries.
 */
public class RPMMetaData implements Iterable<RPMMetaData.RPMMetaValue> {

	private List<RPMMetaValue> values = new ArrayList<>();

	public void addValues(RPMMetaData other) {
		addValues(other.values);
	}

	public void addValues(List<RPMMetaValue> values) {
		this.values.addAll(values);
	}

	/**
	 * Reads the metadata from a pre-sought stream.
	 *
	 * @param in An RPMReader stream.
	 * @throws IOException
	 */
	void readMetaData(RPMReader in) throws IOException {
		int valueCount = in.aligned() ? in.readInt() : in.readUnsignedShort();
		for (int i = 0; i < valueCount; i++) {
			values.add(new RPMMetaValue(in));
		}
	}

	/**
	 * Writes the metadata to a data stream.
	 *
	 * @param out The stream to write into.
	 * @param strtab A String table accessible from the stream.
	 * @throws IOException
	 */
	void writeMetaData(DataOutput out, StringTable strtab) throws IOException {
		out.writeInt(values.size());
		for (RPMMetaValue v : values) {
			v.write(out, strtab);
		}
	}

	/**
	 * Gets the exact size of the meta data as if it was written.
	 *
	 * @return Potential size of the metadata in binary output.
	 */
	public int getByteSize() {
		//int size = 2;
		int size = 4;
		for (RPMMetaValue val : values) {
			size += val.getByteSize();
		}
		return size;
	}

	void addStrings(List<String> l) {
		for (RPMMetaValue val : values) {
			val.addStrings(l);
		}
	}

	/**
	 * Adds a metadata value to this metadata.
	 *
	 * @param val The value to add.
	 */
	public void putValue(RPMMetaValue val) {
		if (val != null) {
			removeValue(findValue(val.name));
			values.add(val);
		}
	}

	/**
	 * Finds a value in this metadata by its name.
	 *
	 * @param name Name of the metadata value.
	 * @return The metadata value with the name, or null if not found.
	 */
	public RPMMetaValue findValue(String name) {
		for (RPMMetaValue v : values) {
			if (v.name.equals(name)) {
				return v;
			}
		}
		return null;
	}

	/**
	 * Removes a metadata value by name.
	 *
	 * @param valName Name of the value to be removed.
	 */
	public void removeValue(String valName) {
		removeValue(findValue(valName));
	}

	/**
	 * Removes a given metadata value.
	 *
	 * @param val The value to remove.
	 */
	public void removeValue(RPMMetaValue val) {
		values.remove(val);
	}

	/**
	 * Gets the number of values in this metadata.
	 *
	 * @return
	 */
	public int getValueCount() {
		return values.size();
	}

	@Override
	public Iterator<RPMMetaValue> iterator() {
		return values.iterator();
	}

	/**
	 * A metadata value in a RPM metadata container.
	 */
	public static class RPMMetaValue {

		public String name;
		public final RPMMetaValueType type;
		private Object value;

		/**
		 * Creates a metadata value.
		 *
		 * @param name Name of the value.
		 * @param value Value of the value. Can only be a String or an integer.
		 */
		public RPMMetaValue(String name, Object value) {
			this.name = name;
			this.value = value;
			if (value == null || value instanceof String) {
				type = RPMMetaValueType.STRING;
			} else if (value instanceof Integer) {
				type = RPMMetaValueType.INT;
			} else if (value instanceof Enum) {
				this.value = ((Enum) value).ordinal();
				type = RPMMetaValueType.INT;
			} else {
				throw new IllegalArgumentException("RPM MetaValues can only be Strings or Integers.");
			}
		}

		private RPMMetaValue(RPMReader in) throws IOException {
			name = in.readStringWithAddress();
			type = RPMMetaValueType.values()[in.read()];
			if (in.aligned()) {
				in.read();
			}
			switch (type) {
				case INT:
					value = (Integer) in.readInt();
					break;
				case STRING:
					value = in.readStringWithAddress();
					if (in.aligned()) {
						in.readShort();
					}
					break;
			}
		}

		/**
		 * Gets the size of the metadata value structure during serialization.
		 *
		 * @return
		 */
		public int getByteSize() {
			int size = 4;
			switch (type) {
				case INT:
					size += 4;
					break;
				case STRING:
					size += 2;
					size += 2; //alignment
					break;
			}
			return size;
		}

		void addStrings(List<String> l) {
			l.add(name);
			if (type == RPMMetaValueType.STRING) {
				l.add(stringValue());
			}
		}

		/**
		 * Gets the value of the metavalue, interpreted as a String.
		 *
		 * @return
		 */
		public String stringValue() {
			return String.valueOf(value);
		}

		/**
		 * Gets the value of the metavalue if it is an Integer. If the value is a String, an exception is thrown.
		 *
		 * @return
		 */
		public int intValue() {
			if (type == RPMMetaValueType.INT) {
				return (Integer) value;
			} else {
				throw new ClassCastException("This is not an integer metavalue!");
			}
		}

		void write(DataOutput out, StringTable strtab) throws IOException {
			strtab.putStringOffset(name);
			out.write(type.ordinal());
			out.write(0);
			if (type == RPMMetaValueType.STRING) {
				strtab.putStringOffset((String) value);
				out.writeShort(0);
			} else {
				out.writeInt((Integer) value);
			}
		}
	}

	public static enum RPMMetaValueType {
		STRING,
		INT
	}
}
