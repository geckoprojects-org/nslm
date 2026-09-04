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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.example.spi.core.SpiClassLoader;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleReference;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Creates and caches the {@link SpiClassLoader} per consumer bundle that the
 * woven call sites hand to {@code java.util.ServiceLoader}. One loader per
 * bundle wiring; a bundle that is refreshed or uninstalled loses its entry.
 */
final class SpiLoaders implements SynchronousBundleListener {

	private final SpiRegistry registry;
	private final Tracing trace;
	private final Map<Long, SpiClassLoader> byBundle = new ConcurrentHashMap<>();

	SpiLoaders(SpiRegistry registry, Tracing trace) {
		this.registry = registry;
		this.trace = trace;
	}

	/**
	 * @return the loader for {@code ServiceLoader.load(Class)} called from
	 *         {@code caller}: a SpiClassLoader in front of the caller's bundle
	 *         class loader; {@code null} if the caller is not a bundle class
	 */
	ClassLoader loaderFor(Class<?> caller) {
		Bundle bundle = bundleOf(caller.getClassLoader());
		if (bundle == null) {
			trace.trace("%s is not a bundle class, plain ServiceLoader", caller.getName());
			return null;
		}
		return cached(bundle);
	}

	/**
	 * @return the loader for {@code ServiceLoader.load(Class, ClassLoader)}: a
	 *         SpiClassLoader in front of {@code loader}, bound to the bundle of
	 *         {@code loader} if it is a bundle class loader, else to the caller's
	 *         bundle; {@code null} if neither is a bundle
	 */
	ClassLoader loaderFor(Class<?> caller, ClassLoader loader) {
		if (loader instanceof SpiClassLoader) {
			return loader;
		}
		Bundle bundle = bundleOf(loader);
		if (bundle != null) {
			BundleWiring wiring = bundle.adapt(BundleWiring.class);
			if (wiring != null && wiring.getClassLoader() == loader) {
				return cached(bundle);
			}
		} else {
			bundle = bundleOf(caller.getClassLoader());
			if (bundle == null) {
				trace.trace("%s is not a bundle class and %s no bundle class loader, plain ServiceLoader",
					caller.getName(), loader);
				return null;
			}
		}
		return new SpiClassLoader(registry, bundle, loader, trace);
	}

	private ClassLoader cached(Bundle bundle) {
		BundleWiring wiring = bundle.adapt(BundleWiring.class);
		ClassLoader bundleLoader = wiring == null ? null : wiring.getClassLoader();
		if (bundleLoader == null) {
			// not resolved (any more): behave like the original call
			byBundle.remove(bundle.getBundleId());
			return null;
		}
		return byBundle.compute(bundle.getBundleId(), (id, existing) -> existing != null
			&& existing.getParent() == bundleLoader ? existing : new SpiClassLoader(registry, bundle, bundleLoader, trace));
	}

	private static Bundle bundleOf(ClassLoader loader) {
		return loader instanceof BundleReference ref ? ref.getBundle() : null;
	}

	@Override
	public void bundleChanged(BundleEvent event) {
		switch (event.getType()) {
			case BundleEvent.UNRESOLVED, BundleEvent.UNINSTALLED, BundleEvent.UPDATED -> byBundle.remove(event.getBundle().getBundleId());
			default -> {
			}
		}
	}
}
