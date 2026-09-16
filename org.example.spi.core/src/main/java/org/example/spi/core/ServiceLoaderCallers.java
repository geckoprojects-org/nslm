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

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Answers whether the current call is part of a {@link ServiceLoader} lookup.
 * <p>
 * {@code META-INF/services/<type>} is an ordinary class loader resource, and code
 * that reads it without the ServiceLoader - a library with its own provider
 * scanner, a build tool, a test - asks for the real files of its own class path.
 * Only a ServiceLoader lookup may be answered with the mediated set of provider
 * bundles. This matters for the thread context class loader of the launcher based
 * mediator, which every bundle in the framework reaches.
 * <p>
 * The class that java.util.ServiceLoader calls {@code getResources} from is an
 * implementation detail of the JDK, so it is not named here but measured once:
 * a probe class loader is handed to a real ServiceLoader lookup and records the
 * innermost frame that belongs to {@link ServiceLoader}. If that measurement
 * fails, the guard opens - never suppress mediation because a stack did not look
 * the way we expected.
 */
final class ServiceLoaderCallers {

	/** class references only, no method info: about half the cost of a full walk */
	private static final StackWalker WALKER = StackWalker
		.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.DROP_METHOD_INFO));

	/** the java.util.ServiceLoader class that calls getResources, or {@code null} if unknown */
	private static final Class<?> RESOURCES_CALLER = calibrate();

	private ServiceLoaderCallers() {
	}

	/**
	 * @return {@code true} if a {@link ServiceLoader} is reading providers in this
	 *         call, or if the caller cannot be recognised at all
	 */
	static boolean isServiceLoaderLookup() {
		Class<?> resourcesCaller = RESOURCES_CALLER;
		if (resourcesCaller == null) {
			return true;
		}
		return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
			.anyMatch(c -> c == resourcesCaller));
	}

	/** @return whether the guard knows what a ServiceLoader lookup looks like */
	static boolean isCalibrated() {
		return RESOURCES_CALLER != null;
	}

	/**
	 * Runs one ServiceLoader lookup against a class loader that answers with no
	 * providers, and keeps the ServiceLoader class that asked for them.
	 */
	private static Class<?> calibrate() {
		try {
			Probe probe = new Probe();
			// no provider is found, so nothing is loaded and no error is raised
			ServiceLoader.load(ServiceLoaderCallers.class, probe).iterator().hasNext();
			return probe.caller;
		} catch (RuntimeException | ServiceConfigurationError | LinkageError e) {
			return null;
		}
	}

	/** records who asked it for provider configuration files */
	private static final class Probe extends ClassLoader {

		private Class<?> caller;

		Probe() {
			super("spi-probe", null);
		}

		@Override
		public Enumeration<URL> getResources(String name) throws IOException {
			caller = WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
				.filter(ServiceLoaderCallers::isServiceLoaderClass)
				.findFirst()
				.orElse(null));
			return Collections.emptyEnumeration();
		}
	}

	/**
	 * A hidden class, for example the body of a lambda inside ServiceLoader, has no
	 * enclosing class and is deliberately not recognised: it would be a different
	 * class on the next lookup.
	 */
	private static boolean isServiceLoaderClass(Class<?> type) {
		return type == ServiceLoader.class || type.getEnclosingClass() == ServiceLoader.class;
	}
}
