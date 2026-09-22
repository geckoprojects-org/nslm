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
import java.util.Set;

/**
 * Target of the woven call sites, for both techniques of
 * {@link ServiceLoaderWeavingHook}.
 * <p>
 * {@code cpool} (default): the method references {@code ServiceLoader.load(Class)}
 * and {@code load(Class, ClassLoader)} in the constant pool are redirected to
 * {@link #load(Class)} and {@link #load(Class, ClassLoader)} here, same names,
 * same descriptors. Nothing else in the class changes, and a method reference
 * {@code ServiceLoader::load} is redirected with them because its method handle
 * constant points to the same entry. The calling class is the frame directly
 * below: the woven class itself, or for a method reference the lambda class the
 * {@code LambdaMetafactory} spun in the woven class's class loader.
 * <p>
 * {@code callsite}: the instructions are rewritten
 * <pre>
 * ServiceLoader.load(type)          to  ServiceLoaders.load(type, CallingClass.class)
 * ServiceLoader.load(type, loader)  to  ServiceLoaders.load(type, loader, CallingClass.class)
 * </pre>
 * and a method reference {@code ServiceLoader::load}, which has no call
 * instruction, gets its {@code invokedynamic} bootstrap argument redirected to
 * {@link #loadFrom}, with the calling class as a captured argument of the
 * lambda. The calling class is pushed as a class constant, no stack walk.
 * <p>
 * Either way the result is a real {@link java.util.ServiceLoader} whose class
 * loader is a {@code SpiClassLoader} bound to the consumer bundle: it hands out
 * the {@code META-INF/services} entries of the provider bundles of the
 * consumer's class space and loads the provider classes from their bundles;
 * everything else goes to the class loader the original call would have used.
 * <p>
 * While the weaver is not active (or the caller is not a bundle class) the
 * calls behave exactly like {@code java.util.ServiceLoader}.
 */
public final class ServiceLoaders {

	private static final StackWalker WALKER = StackWalker
		.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));

	private static volatile SpiLoaders loaders;

	private ServiceLoaders() {
	}

	static void install(SpiLoaders active) {
		loaders = active;
	}

	/** constant pool redirect of {@link ServiceLoader#load(Class)} */
	public static <S> ServiceLoader<S> load(Class<S> service) {
		return load(service, caller());
	}

	/** constant pool redirect of {@link ServiceLoader#load(Class, ClassLoader)} */
	public static <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader) {
		return load(service, loader, caller());
	}

	/**
	 * The class that called {@link #load(Class)} or {@link #load(Class, ClassLoader)}:
	 * the first frame outside this class, hidden frames included, because the
	 * lambda class of a method reference is hidden but lives in the class loader
	 * of the class that holds the reference. The LambdaForms of a plain
	 * {@code MethodHandle} invocation ({@code java.lang.invoke}) are skipped.
	 */
	private static Class<?> caller() {
		return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
			.filter(c -> c != ServiceLoaders.class && !c.getName().startsWith("java.lang.invoke."))
			.findFirst()
			.orElse(ServiceLoaders.class));
	}

	/** call site replacement for {@link ServiceLoader#load(Class)}; also used by the constant pool redirect */
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

	/** call site replacement for {@link ServiceLoader#load(Class, ClassLoader)}; also used by the constant pool redirect */
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
