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
package org.example.spi.equinox;

import org.eclipse.osgi.internal.hookregistry.ActivatorHookFactory;
import org.eclipse.osgi.internal.hookregistry.HookConfigurator;
import org.eclipse.osgi.internal.hookregistry.HookRegistry;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;
import org.example.spi.mediator.equinox.SpiClassLoaderHook;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * The whole deployment of this mediator: Equinox finds the class name in
 * {@code hookconfigurators.properties} on the class loader that loaded
 * {@code org.eclipse.osgi} and calls {@link #addHooks(HookRegistry)} while the
 * framework is being constructed. No launcher extension, no framework property,
 * no bytecode weaving.
 * <p>
 * At that point there is no {@code BundleContext} yet, so the registry cannot be
 * opened. {@link ActivatorHookFactory} closes that gap: the returned activator is
 * started by {@code FrameworkExtensionInstaller.startExtensionActivators} with
 * the system bundle context. That is also why this needs neither a
 * {@code Fragment-Host} nor an {@code ExtensionBundle-Activator}, and why the jar
 * must not be installed as a bundle - it belongs on the framework class path,
 * where the properties scan can see it.
 * <p>
 * What this cannot do is what the launcher based mediator adds: no thread context
 * class loader of its own (Equinox's {@code ContextFinder} is the TCCL here) and
 * nothing on Felix, which has no hook registry.
 */
public class ServiceLoaderHookConfigurator implements HookConfigurator {

	public static final String PROP_TRACE = "spi.equinox.trace";
	public static final String PROP_PROVIDER_STATES = "spi.equinox.providerStates";
	public static final String PROP_SERVICE_LOADER_ONLY = "spi.equinox.serviceLoaderOnly";

	@Override
	public void addHooks(HookRegistry hookRegistry) {
		String traceValue = hookRegistry.getConfiguration().getConfiguration(PROP_TRACE);
		Tracing trace = Tracing.of("spi.equinox", Boolean.parseBoolean(String.valueOf(traceValue)));

		SpiRegistry registry = new SpiRegistry(trace);
		registry.setServiceLoaderOnly(
			!"false".equalsIgnoreCase(hookRegistry.getConfiguration().getConfiguration(PROP_SERVICE_LOADER_ONLY)));

		int states = "active".equalsIgnoreCase(hookRegistry.getConfiguration().getConfiguration(PROP_PROVIDER_STATES))
			? Bundle.STARTING | Bundle.ACTIVE
			: Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING;

		hookRegistry.addClassLoaderHook(new SpiClassLoaderHook(registry, trace));
		hookRegistry.addActivatorHookFactory(() -> new RegistryActivator(registry, trace, states));
		trace.trace("Equinox framework extension: ClassLoaderHook installed by hookconfigurators.properties");
	}

	/** opens the registry once the system bundle context exists, closes it on shutdown */
	private static final class RegistryActivator implements BundleActivator {

		private final SpiRegistry registry;
		private final Tracing trace;
		private final int states;

		RegistryActivator(SpiRegistry registry, Tracing trace, int states) {
			this.registry = registry;
			this.trace = trace;
			this.states = states;
		}

		@Override
		public void start(BundleContext context) {
			registry.open(context, states);
			trace.trace("registry opened from the system bundle context");
		}

		@Override
		public void stop(BundleContext context) {
			registry.close();
		}
	}
}
