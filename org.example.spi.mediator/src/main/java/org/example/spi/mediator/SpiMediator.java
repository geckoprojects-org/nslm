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
package org.example.spi.mediator;

import java.util.Map;

import org.example.spi.core.SpiClassLoader;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;
import org.example.spi.mediator.felix.FelixAdapter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import aQute.launcher.spi.LauncherExtension;

/**
 * Entry point, declared as {@code Embedded-Activator} of this -runpath jar.
 * <p>
 * {@link #beforeFramework}: installs the {@link SpiClassLoader} as thread
 * context class loader of the launching thread (inherited by every thread the
 * framework and the bundles create; Equinox makes it the parent of its
 * ContextFinder) and adds the framework specific hook for bundle class
 * loaders. {@link #start}: opens the registry on the system bundle context,
 * before the first bundle is installed ({@code IMMEDIATE = AFTER_FRAMEWORK_INIT}).
 * <p>
 * Properties (framework configuration / -runproperties):
 * <ul>
 * <li>{@code spi.mediator.enabled} (default true)</li>
 * <li>{@code spi.mediator.trace} (default false)</li>
 * <li>{@code spi.mediator.bundleLoaderHooks} (default true): also hook the
 * bundle class loaders, for ServiceLoader.load(Class, ClassLoader)</li>
 * <li>{@code spi.mediator.serviceLoaderOnly} (default true): mediate only what a
 * {@code java.util.ServiceLoader} reads; false also serves a library that scans
 * {@code META-INF/services} itself</li>
 * <li>{@code spi.mediator.providerStates} (default {@code resolved}): which bundles
 * contribute providers. {@code resolved} = RESOLVED|STARTING|ACTIVE|STOPPING: like
 * packages, providers exist as soon as the bundle is resolved and stay while it is
 * stopped; the mediator never starts a bundle. {@code active} = STARTING|ACTIVE
 * (service like semantics).</li>
 * </ul>
 */
public class SpiMediator implements BundleActivator, LauncherExtension {

	public static final String IMMEDIATE = "AFTER_FRAMEWORK_INIT";

	public static final String PROP_ENABLED = "spi.mediator.enabled";
	public static final String PROP_TRACE = "spi.mediator.trace";
	public static final String PROP_BUNDLE_LOADER_HOOKS = "spi.mediator.bundleLoaderHooks";
	public static final String PROP_PROVIDER_STATES = "spi.mediator.providerStates";
	public static final String PROP_SERVICE_LOADER_ONLY = "spi.mediator.serviceLoaderOnly";

	private static final String FELIX_MARKER = "org/apache/felix/framework/Felix.class";
	private static final String EQUINOX_MARKER = "org/eclipse/osgi/launch/Equinox.class";

	private Tracing trace = Tracing.OFF;
	private SpiRegistry registry;
	private boolean enabled = true;
	private int states = Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING;

	@Override
	public void beforeFramework(Map<String, Object> configuration, ClassLoader runpath) {
		enabled = flag(configuration, PROP_ENABLED, true);
		if (!enabled) {
			return;
		}
		trace = Tracing.of("spi.mediator", flag(configuration, PROP_TRACE, false));
		if ("active".equalsIgnoreCase(String.valueOf(configuration.get(PROP_PROVIDER_STATES)))) {
			states = Bundle.STARTING | Bundle.ACTIVE;
		}
		registry = new SpiRegistry(trace);
		registry.setServiceLoaderOnly(flag(configuration, PROP_SERVICE_LOADER_ONLY, true));

		Thread thread = Thread.currentThread();
		SpiClassLoader tccl = new SpiClassLoader(registry, thread.getContextClassLoader(), trace);
		thread.setContextClassLoader(tccl);
		trace.trace("TCCL of %s set to %s (parent %s)", thread.getName(), tccl.getName(), tccl.getParent());

		boolean hooks = flag(configuration, PROP_BUNDLE_LOADER_HOOKS, true);
		if (runpath.getResource(FELIX_MARKER) != null) {
			trace.trace("framework: Felix");
			if (hooks) {
				FelixAdapter.configure(configuration, registry, trace);
			}
		} else if (runpath.getResource(EQUINOX_MARKER) != null) {
			trace.trace("framework: Equinox");
			// our TCCL must stay the parent of the ContextFinder
			configuration.putIfAbsent("osgi.contextClassLoaderParent", "ccl");
			if (hooks) {
				org.example.spi.mediator.equinox.EquinoxAdapter.configure(configuration, registry, trace);
			}
		} else {
			trace.trace("framework: unknown, TCCL mode only");
		}
	}

	@Override
	public void start(BundleContext context) {
		if (!enabled) {
			return;
		}
		registry.open(context, states);
	}

	@Override
	public void stop(BundleContext context) {
		if (registry != null) {
			registry.close();
		}
	}

	private static boolean flag(Map<String, Object> configuration, String key, boolean defaultValue) {
		Object value = configuration.get(key);
		if (value == null) {
			value = System.getProperty(key);
		}
		return value == null ? defaultValue : Boolean.parseBoolean(value.toString().trim());
	}
}
