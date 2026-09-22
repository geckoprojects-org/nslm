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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redirects method references in the constant pool of a class file to other
 * owner classes, without touching anything else:
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
 * <p>
 * Several {@link Rule}s are applied in one pass, each redirected owner gets
 * one new {@code Class} entry.
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

	/**
	 * @param owner internal name of the class whose method references are redirected
	 * @param newOwner internal name of the new owner (ASCII); it must declare the
	 *            methods with the same names and descriptors
	 * @param methods {@code name + descriptor} of the methods to redirect, e.g.
	 *            {@code "load(Ljava/lang/Class;)Ljava/util/ServiceLoader;"}
	 */
	record Rule(String owner, String newOwner, Set<String> methods) {
	}

	private ConstantPoolPatcher() {
	}

	/**
	 * @param bytes a class file
	 * @param rules the redirections to apply
	 * @return the patched class file, or {@code null} if no method reference
	 *         matched or the class file is not understood
	 */
	static Result redirect(byte[] bytes, List<Rule> rules) {
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

		// method ref index -> rule, in pool order
		Map<Integer, Rule> matches = new LinkedHashMap<>();
		for (int i = 1; i < count; i++) {
			if (tags[i] != METHOD_REF) {
				continue;
			}
			int classIndex = u2(bytes, offsets[i] + 1);
			int natIndex = u2(bytes, offsets[i] + 3);
			if (tags[classIndex] != CLASS || tags[natIndex] != NAME_AND_TYPE) {
				continue;
			}
			int ownerIndex = u2(bytes, offsets[classIndex] + 1);
			for (Rule rule : rules) {
				if (!utf8Equals(bytes, offsets, tags, ownerIndex, rule.owner())) {
					continue;
				}
				int nameIndex = u2(bytes, offsets[natIndex] + 1);
				int descriptorIndex = u2(bytes, offsets[natIndex] + 3);
				if (tags[nameIndex] == UTF8 && tags[descriptorIndex] == UTF8
					&& rule.methods().contains(utf8(bytes, offsets[nameIndex]) + utf8(bytes, offsets[descriptorIndex]))) {
					matches.put(i, rule);
				}
				break;
			}
		}
		if (matches.isEmpty()) {
			return null;
		}

		// one Utf8 + Class pair per new owner, appended in the order of first use
		Map<String, Integer> newClassIndex = new LinkedHashMap<>();
		int next = count;
		int added = 0;
		for (Rule rule : matches.values()) {
			if (!newClassIndex.containsKey(rule.newOwner())) {
				newClassIndex.put(rule.newOwner(), next + 1);
				next += 2;
				added += 3 + rule.newOwner().length() + 3;
			}
		}
		if (next > 0xFFFF) {
			return null;
		}
		byte[] out = new byte[bytes.length + added];
		System.arraycopy(bytes, 0, out, 0, poolEnd);
		int p = poolEnd;
		for (Map.Entry<String, Integer> entry : newClassIndex.entrySet()) {
			byte[] ownerName = entry.getKey().getBytes(StandardCharsets.US_ASCII);
			out[p++] = UTF8;
			p = put2(out, p, ownerName.length);
			System.arraycopy(ownerName, 0, out, p, ownerName.length);
			p += ownerName.length;
			out[p++] = CLASS;
			p = put2(out, p, entry.getValue() - 1);
		}
		System.arraycopy(bytes, poolEnd, out, p, bytes.length - poolEnd);
		put2(out, 8, next);
		for (Map.Entry<Integer, Rule> match : matches.entrySet()) {
			put2(out, offsets[match.getKey()] + 1, newClassIndex.get(match.getValue().newOwner()));
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

	/** names and descriptors of interest are ASCII, where modified UTF-8 and ISO 8859-1 agree */
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
