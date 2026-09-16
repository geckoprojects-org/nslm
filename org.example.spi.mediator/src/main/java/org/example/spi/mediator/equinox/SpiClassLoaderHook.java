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
package org.example.spi.mediator.equinox;

import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.osgi.internal.hookregistry.ClassLoaderHook;
import org.eclipse.osgi.internal.loader.ModuleClassLoader;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;
import org.osgi.framework.Bundle;

/**
 * <ul>
 * <li>{@code preFindResources}: {@code META-INF/services/<type>} requests of a
 * bundle class loader are answered from the registry (class space filtered for
 * that bundle). The registry also contains the asking bundle's own entries, so
 * the answer is complete and Equinox does not search further.</li>
 * <li>{@code postFindClass}: a provider class the bundle cannot load itself is
 * loaded from its provider bundle. That is exactly what
 * {@code Class.forName(impl, false, bundleLoader)} in java.util.ServiceLoader
 * needs.</li>
 * </ul>
 */
public class SpiClassLoaderHook extends ClassLoaderHook {

	private final SpiRegistry registry;
	private final Tracing trace;
	private final ThreadLocal<Set<String>> reentrant = ThreadLocal.withInitial(HashSet::new);

	public SpiClassLoaderHook(SpiRegistry registry, Tracing trace) {
		this.registry = registry;
		this.trace = trace;
	}

	@Override
	public Enumeration<URL> preFindResources(String name, ModuleClassLoader classLoader) throws FileNotFoundException {
		String serviceName = SpiRegistry.serviceNameOf(name);
		if (serviceName == null) {
			return null;
		}
		Bundle bundle = classLoader.getBundle();
		List<URL> urls = registry.serviceResources(serviceName, bundle);
		if (urls.isEmpty()) {
			return null;
		}
		trace.trace("equinox hook: %s for %s -> %d provider bundle(s)", serviceName, bundle.getSymbolicName(), urls.size());
		return Collections.enumeration(urls);
	}

	@Override
	public URL preFindResource(String name, ModuleClassLoader classLoader) throws FileNotFoundException {
		String serviceName = SpiRegistry.serviceNameOf(name);
		if (serviceName == null) {
			return null;
		}
		List<URL> urls = registry.serviceResources(serviceName, classLoader.getBundle());
		return urls.isEmpty() ? null : urls.get(0);
	}

	@Override
	public Class<?> postFindClass(String name, ModuleClassLoader classLoader) throws ClassNotFoundException {
		if (!registry.isProviderClass(name)) {
			return null;
		}
		Bundle consumer = classLoader.getBundle();
		Bundle provider = registry.providerBundle(name, consumer);
		if (provider == null || provider.getBundleId() == consumer.getBundleId()) {
			return null;
		}
		String key = consumer.getBundleId() + ":" + name;
		Set<String> active = reentrant.get();
		if (!active.add(key)) {
			return null;
		}
		try {
			trace.trace("equinox hook: provider class %s for %s from bundle %s", name, consumer.getSymbolicName(), provider.getSymbolicName());
			return provider.loadClass(name);
		} finally {
			active.remove(key);
		}
	}
}
