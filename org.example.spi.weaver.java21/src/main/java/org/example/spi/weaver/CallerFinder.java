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

import java.util.EnumSet;
import java.util.Set;

/**
 * Finds the class that called a redirect target of this package: the first
 * frame outside this package, hidden frames included (the lambda class of a
 * method reference), {@code java.lang.invoke} frames skipped.
 * <p>
 * Java 21 version of the class in {@code org.example.spi.weaver}:
 * {@code StackWalker.Option.DROP_METHOD_INFO} exists from Java 22 on and is
 * looked up by name, so that this variant also walks cheaply on a newer JVM.
 */
final class CallerFinder {

	private static final StackWalker WALKER = StackWalker.getInstance(options());
	private static final String OWN_PACKAGE = CallerFinder.class.getPackageName();

	private CallerFinder() {
	}

	private static Set<StackWalker.Option> options() {
		Set<StackWalker.Option> options = EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE,
			StackWalker.Option.SHOW_HIDDEN_FRAMES);
		for (StackWalker.Option option : StackWalker.Option.values()) {
			if (option.name().equals("DROP_METHOD_INFO")) {
				options.add(option);
			}
		}
		return options;
	}

	static Class<?> caller() {
		return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
			.filter(c -> !c.getPackageName().equals(OWN_PACKAGE) && !c.getName().startsWith("java.lang.invoke."))
			.findFirst()
			.orElse(CallerFinder.class));
	}
}
