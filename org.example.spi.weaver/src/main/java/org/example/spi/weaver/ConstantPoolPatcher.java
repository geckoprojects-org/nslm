/**
 * Copyright (c) 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.example.spi.weaver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redirects method references in the constant pool of a class file to another
 * owner class, without touching anything else:
 * <pre>
 * MethodRef  -> Class java/util/ServiceLoader, load:(Ljava/lang/Class;)Ljava/util/ServiceLoader;
 *   becomes
 * MethodRef  -> Class org/example/spi/weaver/ServiceLoaders (new), same NameAndType
 * </pre>
 * The new {@code Utf8} and {@code Class} entries are appended to the constant
 * pool, so every existing index stays valid: no instruction, no stack map
 * frame, no descriptor and no attribute changes. Every use of the entry is
 * redirected at once, the {@code invokestatic} instructions as well as the
 * {@code MethodHandle} constants a method reference {@code ServiceLoader::load}
 * compiles to. The target must declare the methods with the same names and
 * descriptors.
 * <p>
 * Only the constant pool is parsed, which has kept its format since Java 11
 * ({@code Dynamic}); an unknown entry type leaves the class alone, whatever
 * the class file version.
 */
final class ConstantPoolPatcher {

	private static final int MAGIC = 0xCAFEBABE;
	private static final int UTF8 = 1;
	private static final int CLASS = 7;
	private static final int METHOD_REF = 10;
	private static final int NAME_AND_TYPE = 12;

	/**
	 * @param bytes the patched class file
	 * @param redirected number of method references redirected
	 */
	record Result(byte[] bytes, int redirected) {
	}

	private ConstantPoolPatcher() {
	}

	/**
	 * @param bytes a class file
	 * @param owner internal name of the class whose method references are redirected
	 * @param name method name
	 * @param descriptors method descriptors to redirect
	 * @param newOwner internal name of the new owner (ASCII)
	 * @return the patched class file, or {@code null} if no method reference
	 *         matched or the class file is not understood
	 */
	static Result redirect(byte[] bytes, String owner, String name, Set<String> descriptors, String newOwner) {
		if (bytes.length < 10 || u4(bytes, 0) != MAGIC) {
			return null;
		}
		int count = u2(bytes, 8);
		int[] offsets = new int[count];
		int[] tags = new int[count];
		int pos = 10;
		for (int i = 1; i < count; i++) {
			offsets[i] = pos;
			int tag = bytes[pos] & 0xFF;
			tags[i] = tag;
			switch (tag) {
				case UTF8 -> pos += 3 + u2(bytes, pos + 1);
				case CLASS, 8, 16, 19, 20 -> pos += 3; // Class, String, MethodType, Module, Package
				case 15 -> pos += 4; // MethodHandle
				case 3, 4, 9, METHOD_REF, 11, NAME_AND_TYPE, 17, 18 -> pos += 5;
				case 5, 6 -> { // Long, Double take two slots
					pos += 9;
					i++;
				}
				default -> {
					return null;
				}
			}
		}
		int poolEnd = pos;

		List<Integer> matches = new ArrayList<>();
		for (int i = 1; i < count; i++) {
			if (tags[i] != METHOD_REF) {
				continue;
			}
			int classIndex = u2(bytes, offsets[i] + 1);
			int natIndex = u2(bytes, offsets[i] + 3);
			if (tags[classIndex] != CLASS || tags[natIndex] != NAME_AND_TYPE
				|| !utf8Equals(bytes, offsets, tags, u2(bytes, offsets[classIndex] + 1), owner)
				|| !utf8Equals(bytes, offsets, tags, u2(bytes, offsets[natIndex] + 1), name)) {
				continue;
			}
			int descriptorIndex = u2(bytes, offsets[natIndex] + 3);
			if (tags[descriptorIndex] == UTF8 && descriptors.contains(utf8(bytes, offsets[descriptorIndex]))) {
				matches.add(i);
			}
		}
		if (matches.isEmpty() || count + 2 > 0xFFFF) {
			return null;
		}

		byte[] ownerName = newOwner.getBytes(StandardCharsets.US_ASCII);
		int added = 3 + ownerName.length + 3;
		byte[] out = new byte[bytes.length + added];
		System.arraycopy(bytes, 0, out, 0, poolEnd);
		int utf8Index = count;
		int classIndex = count + 1;
		int p = poolEnd;
		out[p++] = UTF8;
		p = put2(out, p, ownerName.length);
		System.arraycopy(ownerName, 0, out, p, ownerName.length);
		p += ownerName.length;
		out[p++] = CLASS;
		p = put2(out, p, utf8Index);
		System.arraycopy(bytes, poolEnd, out, p, bytes.length - poolEnd);
		put2(out, 8, count + 2);
		for (int ref : matches) {
			put2(out, offsets[ref] + 1, classIndex);
		}
		return new Result(out, matches.size());
	}

	private static boolean utf8Equals(byte[] bytes, int[] offsets, int[] tags, int index, String ascii) {
		if (index <= 0 || index >= tags.length || tags[index] != UTF8) {
			return false;
		}
		int off = offsets[index];
		int length = u2(bytes, off + 1);
		if (length != ascii.length()) {
			return false;
		}
		for (int i = 0; i < length; i++) {
			if (bytes[off + 3 + i] != (byte) ascii.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	/** descriptors of interest are ASCII, where modified UTF-8 and ISO 8859-1 agree */
	private static String utf8(byte[] bytes, int offset) {
		return new String(bytes, offset + 3, u2(bytes, offset + 1), StandardCharsets.ISO_8859_1);
	}

	private static int u2(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
	}

	private static int u4(byte[] bytes, int offset) {
		return (u2(bytes, offset) << 16) | u2(bytes, offset + 2);
	}

	private static int put2(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 8);
		bytes[offset + 1] = (byte) value;
		return offset + 2;
	}
}
