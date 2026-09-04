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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;

/**
 * Knows every ServiceLoader provider of every resolved bundle: service type,
 * provider class, bundle, and the package capability of the service type's
 * package the provider bundle is wired to. The last one is what keeps class
 * spaces apart when the same API package exists in several versions.
 * <p>
 * Sources, in this order: {@code META-INF/services/*} (host plus attached
 * fragments) and, if present, {@code provides} clauses of
 * {@code module-info.class}. No OSGi Service Loader Mediator metadata is read
 * or required.
 */
public final class SpiRegistry implements BundleTrackerCustomizer<List<ProviderEntry>> {

	static final String SERVICES_DIR = "META-INF/services";
	static final String MODULE_INFO = "module-info.class";

	private final Tracing trace;
	private final SpiUrlHandler urls = new SpiUrlHandler(this);
	private final Map<Long, List<ProviderEntry>> byBundle = new ConcurrentHashMap<>();
	private volatile Map<String, List<ProviderEntry>> byService = Map.of();
	private volatile Map<String, List<ProviderEntry>> byImpl = Map.of();
	private BundleTracker<List<ProviderEntry>> tracker;

	public SpiRegistry(Tracing trace) {
		this.trace = Objects.requireNonNull(trace);
	}

	/** @param states bundle state mask, e.g. {@code Bundle.STARTING | Bundle.ACTIVE} */
	public synchronized void open(BundleContext context, int states) {
		if (tracker != null) {
			return;
		}
		tracker = new BundleTracker<>(context, states, this);
		tracker.open();
		trace.trace("registry opened, %d provider entries from %d bundles", byImpl.size(), byBundle.size());
	}

	public synchronized void close() {
		if (tracker != null) {
			tracker.close();
			tracker = null;
		}
	}

	// --- queries -----------------------------------------------------------

	/**
	 * @param serviceName service type
	 * @param consumer the bundle asking, or {@code null} if unknown
	 * @return providers the consumer may use; if the consumer is known and wired
	 *         to the service type's package, only providers wired to the same
	 *         package capability
	 */
	public List<ProviderEntry> providers(String serviceName, Bundle consumer) {
		List<ProviderEntry> all = byService.getOrDefault(serviceName, List.of());
		if (all.isEmpty() || consumer == null) {
			return all;
		}
		String pkg = packageOf(serviceName);
		BundleCapability consumerApi = packageCapability(consumer, pkg);
		if (consumerApi == null) {
			if (pkg.startsWith("java.") || pkg.isEmpty()) {
				// boot delegated API type: one class space for everybody
				return all;
			}
			// The consumer neither imports nor exports the package. Either it holds a private
			// copy of the API (then no foreign provider is type compatible) or it cannot see
			// the API at all (then it is not a consumer of it). Both cases: nothing.
			trace.trace("bundle %s is not wired to %s: no providers for %s", consumer.getSymbolicName(), pkg, serviceName);
			return List.of();
		}
		List<ProviderEntry> compatible = new ArrayList<>(all.size());
		for (ProviderEntry entry : all) {
			if (sameCapability(consumerApi, entry.apiCapability())) {
				compatible.add(entry);
			}
		}
		if (compatible.size() != all.size()) {
			trace.trace("class space filter for %s from bundle %s: %d of %d providers", serviceName,
				consumer.getSymbolicName(), compatible.size(), all.size());
		}
		return compatible;
	}

	/**
	 * @param resourceName a class loader resource name
	 * @return the service type if the name is {@code META-INF/services/<type>}, else {@code null}
	 */
	public static String serviceNameOf(String resourceName) {
		String prefix = SERVICES_DIR + "/";
		if (resourceName.startsWith(prefix) && resourceName.length() > prefix.length()) {
			return resourceName.substring(prefix.length());
		}
		return null;
	}

	/**
	 * The META-INF/services resources java.util.ServiceLoader has to read for the
	 * providers the consumer may use: the real {@code META-INF/services/<type>}
	 * entries of the provider bundles (host and fragments). Only a bundle that
	 * declares (some of) its providers solely in module-info gets a synthetic
	 * {@code spi:} resource instead, listing all its providers of the type.
	 */
	public List<URL> serviceResources(String serviceName, Bundle consumer) {
		List<ProviderEntry> entries = providers(serviceName, consumer);
		if (entries.isEmpty()) {
			return List.of();
		}
		Map<Bundle, List<ProviderEntry>> byProviderBundle = new LinkedHashMap<>();
		for (ProviderEntry entry : entries) {
			byProviderBundle.computeIfAbsent(entry.bundle(), k -> new ArrayList<>()).add(entry);
		}
		Set<URL> result = new LinkedHashSet<>();
		for (Map.Entry<Bundle, List<ProviderEntry>> e : byProviderBundle.entrySet()) {
			boolean allFromFiles = true;
			for (ProviderEntry entry : e.getValue()) {
				if (entry.source() == null) {
					allFromFiles = false;
					break;
				}
			}
			if (allFromFiles) {
				for (ProviderEntry entry : e.getValue()) {
					result.add(entry.source());
				}
			} else {
				result.add(urls.url(serviceName, e.getKey()));
			}
		}
		return new ArrayList<>(result);
	}

	public boolean isProviderClass(String className) {
		return byImpl.containsKey(className);
	}

	/**
	 * @return the bundle that must load the provider class, preferring the one
	 *         whose API capability matches the consumer's; {@code null} if unknown
	 */
	public Bundle providerBundle(String className, Bundle consumer) {
		List<ProviderEntry> entries = byImpl.get(className);
		if (entries == null || entries.isEmpty()) {
			return null;
		}
		if (entries.size() > 1 && consumer != null) {
			for (ProviderEntry entry : entries) {
				BundleCapability consumerApi = packageCapability(consumer, entry.servicePackage());
				if (consumerApi != null && sameCapability(consumerApi, entry.apiCapability())) {
					return entry.bundle();
				}
			}
		}
		return entries.get(0).bundle();
	}

	// --- BundleTrackerCustomizer -------------------------------------------

	@Override
	public List<ProviderEntry> addingBundle(Bundle bundle, BundleEvent event) {
		if (isFragment(bundle)) {
			return null;
		}
		List<ProviderEntry> entries = scan(bundle);
		if (entries.isEmpty()) {
			return null;
		}
		byBundle.put(bundle.getBundleId(), entries);
		rebuild();
		trace.trace("bundle %s [%d] (%s): %d provider entries", bundle.getSymbolicName(), bundle.getBundleId(),
			stateName(bundle.getState()), entries.size());
		return entries;
	}

	@Override
	public void modifiedBundle(Bundle bundle, BundleEvent event, List<ProviderEntry> object) {
		// state changes within the tracked mask (RESOLVED..STOPPING) do not change the content
	}

	@Override
	public void removedBundle(Bundle bundle, BundleEvent event, List<ProviderEntry> object) {
		byBundle.remove(bundle.getBundleId());
		rebuild();
		trace.trace("bundle %s [%d]: providers removed", bundle.getSymbolicName(), bundle.getBundleId());
	}

	private synchronized void rebuild() {
		Map<String, List<ProviderEntry>> service = new HashMap<>();
		Map<String, List<ProviderEntry>> impl = new HashMap<>();
		for (List<ProviderEntry> entries : byBundle.values()) {
			for (ProviderEntry entry : entries) {
				service.computeIfAbsent(entry.serviceName(), k -> new ArrayList<>()).add(entry);
				impl.computeIfAbsent(entry.implName(), k -> new ArrayList<>()).add(entry);
			}
		}
		service.replaceAll((k, v) -> Collections.unmodifiableList(v));
		impl.replaceAll((k, v) -> Collections.unmodifiableList(v));
		byService = Map.copyOf(service);
		byImpl = Map.copyOf(impl);
	}

	static String stateName(int state) {
		switch (state) {
			case Bundle.INSTALLED : return "INSTALLED";
			case Bundle.RESOLVED : return "RESOLVED";
			case Bundle.STARTING : return "STARTING";
			case Bundle.ACTIVE : return "ACTIVE";
			case Bundle.STOPPING : return "STOPPING";
			default : return String.valueOf(state);
		}
	}

	// --- scanning ----------------------------------------------------------

	List<ProviderEntry> scan(Bundle bundle) {
		// service name -> impl name -> source file (null = module-info)
		Map<String, Map<String, URL>> found = new LinkedHashMap<>();
		// host plus attached fragments; the bundle is at least RESOLVED, so this does not resolve anything
		Enumeration<URL> files = bundle.findEntries(SERVICES_DIR, "*", false);
		if (files != null) {
			while (files.hasMoreElements()) {
				URL url = files.nextElement();
				String path = url.getPath();
				String serviceName = path.substring(path.lastIndexOf('/') + 1);
				if (serviceName.isEmpty()) {
					continue;
				}
				try (InputStream in = url.openStream()) {
					Map<String, URL> impls = found.computeIfAbsent(serviceName, k -> new LinkedHashMap<>());
					for (String impl : parseServicesFile(in)) {
						impls.putIfAbsent(impl, url);
					}
				} catch (IOException e) {
					trace.trace("cannot read %s of bundle %s: %s", path, bundle.getSymbolicName(), e);
				}
			}
		}
		URL moduleInfo = bundle.getEntry(MODULE_INFO);
		if (moduleInfo != null) {
			try (InputStream in = moduleInfo.openStream()) {
				for (ModuleDescriptor.Provides provides : ModuleDescriptor.read(in).provides()) {
					Map<String, URL> impls = found.computeIfAbsent(provides.service(), k -> new LinkedHashMap<>());
					for (String impl : provides.providers()) {
						impls.putIfAbsent(impl, null);
					}
				}
			} catch (IOException | InvalidModuleDescriptorException e) {
				trace.trace("cannot read module-info of bundle %s: %s", bundle.getSymbolicName(), e);
			}
		}
		if (found.isEmpty()) {
			return List.of();
		}
		List<ProviderEntry> entries = new ArrayList<>();
		for (Map.Entry<String, Map<String, URL>> e : found.entrySet()) {
			BundleCapability api = packageCapability(bundle, packageOf(e.getKey()));
			for (Map.Entry<String, URL> impl : e.getValue().entrySet()) {
				entries.add(new ProviderEntry(e.getKey(), impl.getKey(), bundle, api, impl.getValue()));
			}
		}
		return List.copyOf(entries);
	}

	/** same rules as java.util.ServiceLoader: '#' comments, trimmed, one name per line */
	static List<String> parseServicesFile(InputStream in) throws IOException {
		List<String> names = new ArrayList<>();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			int comment = line.indexOf('#');
			if (comment >= 0) {
				line = line.substring(0, comment);
			}
			line = line.trim();
			if (!line.isEmpty()) {
				names.add(line);
			}
		}
		return names;
	}

	// --- wiring helpers -----------------------------------------------------

	static String packageOf(String className) {
		int dollar = className.indexOf('$');
		String outer = dollar > 0 ? className.substring(0, dollar) : className;
		int dot = outer.lastIndexOf('.');
		return dot > 0 ? outer.substring(0, dot) : "";
	}

	static boolean isFragment(Bundle bundle) {
		BundleRevision revision = bundle.adapt(BundleRevision.class);
		return revision != null && (revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0;
	}

	/**
	 * @return the package capability the bundle is wired to for {@code pkg}
	 *         (import), or its own export/contained capability, or {@code null}
	 */
	static BundleCapability packageCapability(Bundle bundle, String pkg) {
		BundleWiring wiring = bundle.adapt(BundleWiring.class);
		if (wiring == null || pkg.isEmpty()) {
			return null;
		}
		List<BundleWire> wires = wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE);
		if (wires != null) {
			for (BundleWire wire : wires) {
				if (pkg.equals(wire.getCapability().getAttributes().get(BundleRevision.PACKAGE_NAMESPACE))) {
					return wire.getCapability();
				}
			}
		}
		List<BundleCapability> own = wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE);
		if (own != null) {
			for (BundleCapability capability : own) {
				if (pkg.equals(capability.getAttributes().get(BundleRevision.PACKAGE_NAMESPACE))) {
					return capability;
				}
			}
		}
		return null;
	}

	static boolean sameCapability(BundleCapability a, BundleCapability b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		return a.equals(b) || (a.getRevision().equals(b.getRevision()) && a.getAttributes().equals(b.getAttributes()));
	}
}
