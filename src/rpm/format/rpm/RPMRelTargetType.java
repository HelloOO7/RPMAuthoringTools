/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rpm.format.rpm;

/**
 * Type of the relocated field at the target address,
 */
public enum RPMRelTargetType {
	/**
	 * A 32-bit absolute offset.
	 */
	OFFSET,
	/**
	 * A thumb BL instruction.
	 */
	THUMB_BRANCH_LINK,
	/**
	 * An ARM BL instruction.
	 */
	ARM_BRANCH_LINK,
	/**
	 * A Thumb branch. For technical reasons, it is relocated as a BL with return.
	 */
	THUMB_BRANCH,
	/**
	 * An ARM B instruction.
	 */
	ARM_BRANCH,
	/**
	 * Full copy of the source data.
	 */
	FULL_COPY,
	THUMB_BRANCH_SAFESTACK,
	OFFSET_REL31;

	public static RPMRelTargetType fromName(String name) {
		for (RPMRelTargetType t : values()) {
			if (t.name().equals(name)) {
				return t;
			}
		}
		return null;
	}
	
	public boolean isSelfRelative() {
		switch (this) {
			case ARM_BRANCH:
			case ARM_BRANCH_LINK:
			case THUMB_BRANCH:
			case THUMB_BRANCH_LINK:
			case THUMB_BRANCH_SAFESTACK:
				return true;
		}
		return false;
	}
}
