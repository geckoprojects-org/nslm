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

import java.util.EnumSet;
import java.util.Set;

/**
 * The {@link StackWalker} of the core: class references only. On Java 22 and
 * newer it also drops the method information nobody here needs
 * ({@code StackWalker.Option.DROP_METHOD_INFO}, about half the cost of a full
 * walk, ~1 µs on Java 25); the option is looked up by name because the core is
 * built for Java 21.
 */
final class ClassWalker {

	static final StackWalker WALKER = StackWalker.getInstance(options());

	private ClassWalker() {
	}

	private static Set<StackWalker.Option> options() {
		Set<StackWalker.Option> options = EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE);
		for (StackWalker.Option option : StackWalker.Option.values()) {
			if (option.name().equals("DROP_METHOD_INFO")) {
				options.add(option);
			}
		}
		return options;
	}
}
