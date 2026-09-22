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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;

/**
 * The class loader java.util.ServiceLoader talks to. Two operating modes:
 * <ul>
 * <li><b>TCCL mode</b> (not strict, consumer unknown): installed as thread
 * context class loader by the launcher. {@code getResources("META-INF/services/X")}
 * adds one synthetic resource per provider bundle to whatever the previous TCCL
 * delivers; the asking bundle is determined once per call with a
 * {@link StackWalker}. {@code loadClass} serves provider classes from their
 * bundles and delegates everything else to the previous TCCL. Every bundle in the
 * framework reaches this loader, so only what a {@link java.util.ServiceLoader}
 * reads is mediated, see {@link ServiceLoaderCallers}.</li>
 * <li><b>strict mode</b> (bound to a consumer bundle): plugged in front of the
 * framework's boot class loader for one bundle (Felix
 * {@code felix.bootdelegation.classloaders}). Answers only ServiceLoader
 * resources and provider classes, mirrors the framework's boot delegation for
 * {@code java.*} and configured packages, and fails for everything else so
 * that the framework continues its normal search.</li>
 * </ul>
 */
public final class SpiClassLoader extends ClassLoader {

	static {
		registerAsParallelCapable();
	}

	private final SpiRegistry registry;
	private final Bundle consumer;
	private final boolean strict;
	private final BootDelegation bootDelegation;
	private final ClassLoader boot;
	private final Tracing trace;

	/** TCCL mode */
	public SpiClassLoader(SpiRegistry registry, ClassLoader previousTccl, Tracing trace) {
		super("spi-tccl", previousTccl);
		this.registry = registry;
		this.consumer = null;
		this.strict = false;
		this.bootDelegation = null;
		this.boot = null;
		this.trace = trace;
	}

	/**
	 * Bound mode for one bundle, not strict: the consumer is known (no stack
	 * walk), everything that is not a ServiceLoader resource or provider class is
	 * delegated to {@code parent}. Used by the weaving mediator, which replaces the
	 * class loader a woven {@code ServiceLoader.load} call would have used
	 * (bundle class loader or an explicit argument) with this one.
	 */
	public SpiClassLoader(SpiRegistry registry, Bundle consumer, ClassLoader parent, Tracing trace) {
		super("spi-" + consumer.getSymbolicName(), parent);
		this.registry = registry;
		this.consumer = consumer;
		this.strict = false;
		this.bootDelegation = null;
		this.boot = null;
		this.trace = trace;
	}

	/** strict mode for one bundle */
	public SpiClassLoader(SpiRegistry registry, Bundle consumer, BootDelegation bootDelegation, ClassLoader boot,
		Tracing trace) {
		super("spi-" + consumer.getSymbolicName(), null);
		this.registry = registry;
		this.consumer = consumer;
		this.strict = true;
		this.bootDelegation = bootDelegation;
		this.boot = boot;
		this.trace = trace;
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (registry.isProviderClass(name)) {
			Bundle bundle = registry.providerBundle(name, consumer != null ? consumer : callerBundle());
			if (bundle != null) {
				trace.trace("%s: provider class %s from bundle %s", getName(), name, bundle.getSymbolicName());
				return bundle.loadClass(name);
			}
		}
		if (!strict) {
			return super.loadClass(name, resolve);
		}
		if (bootDelegation.matches(SpiRegistry.packageOf(name))) {
			return boot.loadClass(name);
		}
		throw new ClassNotFoundException(name);
	}

	@Override
	public URL getResource(String name) {
		String serviceName = SpiRegistry.serviceNameOf(name);
		if (serviceName != null) {
			Bundle asking = consumer != null ? consumer : callerBundle();
			List<URL> spi = spiResources(serviceName, asking);
			if (!spi.isEmpty()) {
				return spi.get(0);
			}
			if (asking != null && !strict) {
				return null;
			}
		}
		if (!strict) {
			return super.getResource(name);
		}
		return bootDelegation.matches(packageOfResource(name)) ? boot.getResource(name) : null;
	}

	/**
	 * For {@code META-INF/services/<type>} of a known consumer bundle the registry
	 * is the complete answer: it covers every resolved bundle of the consumer's
	 * class space, the consumer itself included. The parent is only asked when
	 * the consumer is unknown (TCCL mode without a bundle frame on the stack),
	 * and for everything that is not a ServiceLoader resource. Asking the parent
	 * for a resource it does not have is what costs: Equinox, for example, ends a
	 * miss in its compatibility boot delegation, i.e. a scan of the application
	 * class path.
	 * <p>
	 * Mediated is only what a {@link java.util.ServiceLoader} reads. The same
	 * resource requested by a library with its own provider scanner is answered
	 * with the plain resources of the class loader it asked, see
	 * {@link ServiceLoaderCallers}. {@link #getResource(String)} is not guarded
	 * that way: the ServiceLoader never calls it, so the single resource form
	 * exists for exactly those manual lookups.
	 */
	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		String serviceName = SpiRegistry.serviceNameOf(name);
		if (serviceName != null && registry.isServiceLoaderOnly() && !ServiceLoaderCallers.isServiceLoaderLookup()) {
			trace.trace("%s: %s is not read by a ServiceLoader, serving the plain resources", getName(), name);
			// strict mode answers nothing, so the framework continues its normal search
			return strict ? Collections.emptyEnumeration() : super.getResources(name);
		}
		if (serviceName != null) {
			Bundle asking = consumer != null ? consumer : callerBundle();
			List<URL> spi = spiResources(serviceName, asking);
			if (strict) {
				if (!spi.isEmpty()) {
					return Collections.enumeration(spi);
				}
			} else if (asking != null) {
				return Collections.enumeration(spi);
			} else {
				Enumeration<URL> parent = super.getResources(name);
				if (spi.isEmpty()) {
					return parent;
				}
				Set<URL> all = new LinkedHashSet<>(spi);
				while (parent.hasMoreElements()) {
					all.add(parent.nextElement());
				}
				return Collections.enumeration(all);
			}
		} else if (!strict) {
			return super.getResources(name);
		}
		return bootDelegation.matches(packageOfResource(name)) ? boot.getResources(name) : Collections.emptyEnumeration();
	}

	private List<URL> spiResources(String serviceName, Bundle asking) {
		List<URL> result = registry.serviceResources(serviceName, asking);
		if (!result.isEmpty()) {
			trace.trace("%s: %s for %s -> %d provider bundle(s)", getName(), serviceName,
				asking == null ? "<unknown caller>" : asking.getSymbolicName(), result.size());
		}
		return result;
	}

	/**
	 * Class references only, no method info: this is what SecurityManager.getClassContext
	 * does internally as well, see {@link ClassWalker}.
	 */
	private static final StackWalker WALKER = ClassWalker.WALKER;

	/** the first bundle class loader on the stack, i.e. the bundle calling ServiceLoader */
	private Bundle callerBundle() {
		return WALKER
			.walk(frames -> frames.map(f -> f.getDeclaringClass().getClassLoader())
				.filter(cl -> cl instanceof BundleReference && cl != this)
				.map(cl -> ((BundleReference) cl).getBundle())
				.findFirst()
				.orElse(null));
	}

	private static String packageOfResource(String name) {
		int slash = name.lastIndexOf('/');
		return slash > 0 ? name.substring(0, slash).replace('/', '.') : "";
	}
}
