package rpm.format.rpm;

public class RPMRevisions {

	/**
	 * Current revision of the RPM writer.
	 */
	public static final int REV_CURRENT = 13;

	/**
	 * Initial version of the format.
	 */
	public static final int REV_FOUNDATION = 0;

	/**
	 * Added symbol length field.
	 */
	public static final int REV_SYMBOL_LENGTH = 1;

	/**
	 * Added product name and version metadata fields. THIS VERSION HAS BEEN TERMINATED!
	 */
	public static final int REV_PRODUCT_INFO = 2;

	/**
	 * Use the string table to write symbol names. Makes structs more easily deserializable and opens the door
	 * for possible string compression.
	 */
	public static final int REV_SYMBSTR_TABLE = 3;

	/**
	 * Reduce the size of the symbol table by writing string pointers and symbol sizes as shorts.
	 */
	public static final int REV_SMALL_SYMBOLS = 3;

	/**
	 * Write essential header fields into a separately referenced INFO section. This eliminates the need for
	 * reserved values in the RPM footer.
	 */
	public static final int REV_INFO_SECTION = 3;

	/**
	 * Implements the capability to add arbitrary user data to an RPM.
	 */
	public static final int REV_METADATA = 3;

	/**
	 * Omits empty sections completely.
	 */
	public static final int REV_COMPACT_EMPTY_SECTIONS = 4;

	/**
	 * Format redesigned for aligned memory safety.
	 */
	public static final int REV_ALIGNED_FORMAT = 5;

	/**
	 * Pre-generated lists of relocation and symbol extern modules.
	 */
	public static final int REV_EXTERN_LISTS = 6;

	/**
	 * Code segment no longer required to be at the start of the file (0x20-byte prolog).
	 */
	public static final int REV_INDEPENDENT_CODE_SEG = 7;

	/**
	 * Separate internal / external relocation tables.
	 */
	public static final int REV_SEPARATE_RELOCATIONS = 7;

	/**
	 * Import and export symbol hashing for blazing fast lookup.
	 */
	public static final int REV_IMPORT_EXPORT_SYMBOL_HASHTABLES = 8;

	/**
	 * Separate relocation list for imported symbols (allows loading libraries from within module).
	 */
	public static final int REV_IMPORT_RELOCATION_LIST = 9;

	/**
	 * 8-byte relocation structure.
	 */
	public static final int REV_COMPACT_RELOCATIONS = 10;

	/**
	 * BSS expansion on module load -> header-relative control offsets.
	 */
	public static final int REV_BSS_EXPANSION = 11;

	/**
	 * Sorted export symbols for binary search.
	 */
	public static final int REV_SORTED_IMEX_SYMBOLS = 12;
	
	/**
	 * Global address flag moved to SymbolAttr.
	 */
	public static final int REV_GLOBAL_IN_SYMATTR = 12;
	
	/**
	 * Static initializers and finalizers.
	 */
	public static final int REV_SINIT_SFINI = 13;
}
