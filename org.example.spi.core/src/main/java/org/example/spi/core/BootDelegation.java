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
package org.example.spi.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the framework's boot delegation decision ({@code java.*} always,
 * plus the {@code org.osgi.framework.bootdelegation} patterns) so that a class
 * loader plugged in front of the framework's boot loader keeps the framework's
 * class space semantics for everything that is not a ServiceLoader provider.
 */
public final class BootDelegation {

	private final List<String> exact = new ArrayList<>();
	private final List<String> prefixes = new ArrayList<>();

	public BootDelegation(String bootDelegationProperty) {
		if (bootDelegationProperty == null) {
			return;
		}
		for (String pattern : bootDelegationProperty.split(",")) {
			pattern = pattern.trim();
			if (pattern.isEmpty()) {
				continue;
			}
			if (pattern.equals("*")) {
				prefixes.add("");
			} else if (pattern.endsWith(".*")) {
				prefixes.add(pattern.substring(0, pattern.length() - 1)); // keep the dot
			} else {
				exact.add(pattern);
			}
		}
	}

	public boolean matches(String pkg) {
		if (pkg.startsWith("java.")) {
			return true;
		}
		// Felix asks ONLY the boot delegation loader for the JDK's generated reflection
		// accessors (BundleWiringImpl "accessor" case) and fails hard if it does not
		// deliver; the default boot loader (platform class loader) can load them.
		if (pkg.startsWith("jdk.internal.reflect") || pkg.startsWith("sun.reflect")) {
			return true;
		}
		if (exact.contains(pkg)) {
			return true;
		}
		for (String prefix : prefixes) {
			if (pkg.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
