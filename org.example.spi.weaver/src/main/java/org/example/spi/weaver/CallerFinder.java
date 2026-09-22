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

import java.util.Set;

/**
 * Finds the class that called a redirect target of this package
 * ({@link ServiceLoaders#load(Class)}, {@link ServiceLoaders#load(Class, ClassLoader)},
 * {@link JdkFactories}): the first frame outside this package, hidden frames
 * included, because the lambda class of a method reference is hidden but lives
 * in the class loader of the class that holds the reference. The LambdaForms of
 * a plain {@code MethodHandle} invocation ({@code java.lang.invoke}) are skipped.
 * <p>
 * Java 25 version: the walk drops the method information it does not need
 * ({@link StackWalker.Option#DROP_METHOD_INFO}, Java 22+), which halves its
 * cost. {@code org.example.spi.weaver.java21} has its own version of this class.
 */
final class CallerFinder {

	private static final StackWalker WALKER = StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE,
		StackWalker.Option.SHOW_HIDDEN_FRAMES, StackWalker.Option.DROP_METHOD_INFO));
	private static final String OWN_PACKAGE = CallerFinder.class.getPackageName();

	private CallerFinder() {
	}

	static Class<?> caller() {
		return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
			.filter(c -> !c.getPackageName().equals(OWN_PACKAGE) && !c.getName().startsWith("java.lang.invoke."))
			.findFirst()
			.orElse(CallerFinder.class));
	}
}
