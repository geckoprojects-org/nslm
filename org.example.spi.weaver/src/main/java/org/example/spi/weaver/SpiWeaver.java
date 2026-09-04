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

import org.example.spi.core.SpiClassLoader;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;

/**
 * {@code ExtensionBundle-Activator} of the weaving mediator. Runs with the
 * system bundle context as soon as the extension is resolved, i.e. before any
 * regular bundle is started: opens the {@link SpiRegistry} and registers the
 * {@link ServiceLoaderWeavingHook}.
 * <p>
 * Properties (framework configuration / -runproperties / system properties):
 * <ul>
 * <li>{@code spi.weaver.enabled} (default true)</li>
 * <li>{@code spi.weaver.trace} (default false)</li>
 * <li>{@code spi.weaver.providerStates} (default {@code resolved}, alternative
 * {@code active}): same meaning as {@code spi.mediator.providerStates}</li>
 * <li>{@code spi.weaver.tccl} (default false): additionally install a
 * {@link SpiClassLoader} in TCCL mode as thread context class loader of the
 * thread that activates the extension (the launcher thread; every thread
 * created afterwards inherits it). This is what reaches the ServiceLoader
 * calls inside the JDK (JAXP factories, ImageIO, JNDI, ...) that cannot be
 * woven. Best effort: threads the framework created earlier keep their TCCL.
 * The previous TCCL becomes the parent, so Equinox's ContextFinder stays in
 * the chain.</li>
 * </ul>
 * Also usable as plain {@code Bundle-Activator} of a normal bundle; then the
 * bundle must be started before the first consumer class is loaded (start
 * level).
 */
public final class SpiWeaver implements BundleActivator {

	public static final String PROP_ENABLED = "spi.weaver.enabled";
	public static final String PROP_TRACE = "spi.weaver.trace";
	public static final String PROP_PROVIDER_STATES = "spi.weaver.providerStates";
	public static final String PROP_TCCL = "spi.weaver.tccl";

	private SpiRegistry registry;
	private SpiLoaders loaders;
	private ServiceLoaderWeavingHook hook;
	private ServiceRegistration<WeavingHook> hookRegistration;
	private Tracing trace = Tracing.OFF;
	private Thread tcclThread;
	private ClassLoader previousTccl;

	@Override
	public void start(BundleContext context) {
		if (!flag(context, PROP_ENABLED, true)) {
			return;
		}
		trace = Tracing.of("spi.weaver", flag(context, PROP_TRACE, false));
		int states = "active".equalsIgnoreCase(context.getProperty(PROP_PROVIDER_STATES))
			? Bundle.STARTING | Bundle.ACTIVE
			: Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING;

		registry = new SpiRegistry(trace);
		loaders = new SpiLoaders(registry, trace);
		hook = new ServiceLoaderWeavingHook(trace);

		context.addBundleListener(loaders);
		registry.open(context, states);
		ServiceLoaders.install(loaders);
		hookRegistration = context.registerService(WeavingHook.class, hook, null);
		trace.trace("weaving hook registered by bundle %s [%d]", context.getBundle().getSymbolicName(),
			context.getBundle().getBundleId());
		if (flag(context, PROP_TCCL, false)) {
			tcclThread = Thread.currentThread();
			previousTccl = tcclThread.getContextClassLoader();
			SpiClassLoader tccl = new SpiClassLoader(registry, previousTccl, trace);
			tcclThread.setContextClassLoader(tccl);
			trace.trace("TCCL of %s set to %s (parent %s)", tcclThread.getName(), tccl.getName(), previousTccl);
		}
	}

	@Override
	public void stop(BundleContext context) {
		if (tcclThread != null) {
			tcclThread.setContextClassLoader(previousTccl);
			tcclThread = null;
		}
		if (hookRegistration != null) {
			hookRegistration.unregister();
			hookRegistration = null;
		}
		ServiceLoaders.install(null);
		if (registry != null) {
			registry.close();
		}
		if (loaders != null) {
			context.removeBundleListener(loaders);
		}
		if (hook != null) {
			trace.trace("stopped: %d classes, %d call sites woven", hook.wovenClasses(), hook.wovenCallSites());
		}
	}

	private static boolean flag(BundleContext context, String key, boolean defaultValue) {
		String value = context.getProperty(key);
		return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
	}
}
