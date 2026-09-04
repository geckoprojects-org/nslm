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

import java.util.ServiceLoader;

/**
 * Target of the woven call sites. {@link ServiceLoaderWeavingHook} rewrites
 * <pre>
 * ServiceLoader.load(type)          to  ServiceLoaders.load(type, CallingClass.class)
 * ServiceLoader.load(type, loader)  to  ServiceLoaders.load(type, loader, CallingClass.class)
 * </pre>
 * A method reference {@code ServiceLoader::load} has no call instruction; its
 * {@code invokedynamic} bootstrap argument is redirected to {@link #loadFrom}
 * instead and the calling class becomes a captured argument of the lambda.
 * The calling class is pushed as a class constant by the weaver, so the
 * consumer bundle is known without a stack walk. The result is a real
 * {@link java.util.ServiceLoader} whose class loader is a
 * {@code SpiClassLoader} bound to the consumer bundle: it hands out the
 * {@code META-INF/services} entries of the provider bundles of the consumer's
 * class space and loads the provider classes from their bundles; everything
 * else goes to the class loader the original call would have used.
 * <p>
 * While the weaver is not active (or the caller is not a bundle class) the
 * calls behave exactly like {@code java.util.ServiceLoader}.
 */
public final class ServiceLoaders {

	private static volatile SpiLoaders loaders;

	private ServiceLoaders() {
	}

	static void install(SpiLoaders active) {
		loaders = active;
	}

	/** woven replacement for {@link ServiceLoader#load(Class)} */
	public static <S> ServiceLoader<S> load(Class<S> service, Class<?> caller) {
		SpiLoaders active = loaders;
		if (active != null) {
			ClassLoader loader = active.loaderFor(caller);
			if (loader != null) {
				return ServiceLoader.load(service, loader);
			}
		}
		return ServiceLoader.load(service);
	}

	/**
	 * Target of a woven method reference {@code ServiceLoader::load} with one
	 * parameter: the caller is the captured (leading) argument of the lambda.
	 */
	public static <S> ServiceLoader<S> loadFrom(Class<?> caller, Class<S> service) {
		return load(service, caller);
	}

	/** Target of a woven method reference {@code ServiceLoader::load} with two parameters. */
	public static <S> ServiceLoader<S> loadFrom(Class<?> caller, Class<S> service, ClassLoader loader) {
		return load(service, loader, caller);
	}

	/** woven replacement for {@link ServiceLoader#load(Class, ClassLoader)} */
	public static <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader, Class<?> caller) {
		SpiLoaders active = loaders;
		if (active != null && loader != null) {
			ClassLoader spi = active.loaderFor(caller, loader);
			if (spi != null) {
				return ServiceLoader.load(service, spi);
			}
		}
		return ServiceLoader.load(service, loader);
	}
}
