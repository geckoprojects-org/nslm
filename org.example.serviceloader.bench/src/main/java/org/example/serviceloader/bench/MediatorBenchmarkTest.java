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
package org.example.serviceloader.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.Supplier;

import org.example.serviceloader.api.Greeter;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * Runs inside the framework under whichever mediator the bndrun installs.
 * {@link #benchmark()} prints the timings, the probes print
 * {@code PROBE|<setup>|<name>|<observation>} and never fail: they record what
 * a mediator handles, the README turns that into the comparison table.
 */
public class MediatorBenchmarkTest {

	static final String PROVIDER_V1 = "org.example.serviceloader.provider";

	private final BundleContext context = FrameworkUtil.getBundle(MediatorBenchmarkTest.class).getBundleContext();

	String setup() {
		for (Bundle bundle : context.getBundles()) {
			String bsn = bundle.getSymbolicName();
			if ("org.example.spi.weaver".equals(bsn)) {
				return "weaver";
			}
			if (bsn != null && bsn.startsWith("org.apache.aries.spifly")) {
				return "spifly";
			}
		}
		// launcher based mediator: the SpiClassLoader is the TCCL (Felix) or the parent of Equinox's ContextFinder
		for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
			if (cl.getClass().getName().endsWith("SpiClassLoader")) {
				return "mediator";
			}
		}
		return "none";
	}

	@Test
	void benchmark() {
		String setup = setup();
		try {
			var results = Bench.run(Bench.warmup(), Bench.iterations());
			Bench.report(setup, results);
			assertEquals(2, (int) results.get("load + iterate")[1], "providers seen by load + iterate under " + setup);
		} catch (ServiceConfigurationError e) {
			// a mediator that delivers a provider of a foreign class space cannot be measured
			System.out.printf("BENCH|%s|aborted|%s%n", setup, firstLine(e.getMessage()));
			abort("no benchmark under " + setup + ": " + firstLine(e.getMessage()));
		}
	}

	/** ServiceLoader::load as a method reference: no invokestatic in this class, nothing to weave */
	@Test
	void probeMethodReference() {
		Function<Class<Greeter>, ServiceLoader<Greeter>> load = ServiceLoader::load;
		probe("method reference ServiceLoader::load", () -> Bench.count(load.apply(Greeter.class)) + " providers");
	}

	/** a lambda body is an ordinary synthetic method: the call inside it is a normal call site */
	@Test
	void probeLambda() {
		Supplier<ServiceLoader<Greeter>> load = () -> ServiceLoader.load(Greeter.class);
		probe("lambda () -> ServiceLoader.load", () -> Bench.count(load.get()) + " providers");
	}

	/** a nested class is a separate class file: woven on its own */
	@Test
	void probeNestedClass() {
		probe("nested class calling ServiceLoader.load", () -> Bench.count(Nested.load()) + " providers");
	}

	static final class Nested {
		static ServiceLoader<Greeter> load() {
			return ServiceLoader.load(Greeter.class);
		}
	}

	/**
	 * Greeter 2.0 (same package name, other API bundle) and its provider are
	 * installed too. This bundle is wired to Greeter 1.0 and must see two
	 * providers, never the 2.0 one.
	 */
	@Test
	void probeClassSpace() {
		probe("two API versions installed, consumer wired to 1.0", () -> {
			List<String> names = new ArrayList<>();
			try {
				for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
					names.add(greeter.getClass().getSimpleName());
				}
				return names.size() + " providers " + names;
			} catch (ServiceConfigurationError e) {
				return "ServiceConfigurationError after " + names + ": " + firstLine(e.getMessage());
			}
		});
	}

	/** are providers of a RESOLVED (stopped) bundle still delivered? */
	@Test
	void probeStoppedProviderBundle() throws Exception {
		Bundle provider = null;
		for (Bundle bundle : context.getBundles()) {
			if (PROVIDER_V1.equals(bundle.getSymbolicName())) {
				provider = bundle;
			}
		}
		Bundle p = provider;
		try {
			p.stop();
			Thread.sleep(200);
			probe("provider bundle stopped (RESOLVED)", () -> Bench.count(ServiceLoader.load(Greeter.class)) + " providers");
		} finally {
			p.start();
			Thread.sleep(200);
		}
		probe("provider bundle started again", () -> Bench.count(ServiceLoader.load(Greeter.class)) + " providers");
	}

	/**
	 * A ServiceLoader call inside the JDK: javax.xml.stream.FactoryFinder asks the
	 * TCCL. Woodstox is installed; the JDK default is com.sun.xml.internal.stream.
	 */
	@Test
	void probeJdkFactoryLookup() {
		probe("XMLInputFactory.newInstance() (ServiceLoader inside the JDK, TCCL)",
			() -> javax.xml.stream.XMLInputFactory.newInstance().getClass().getName());
	}

	private void probe(String name, Supplier<String> observation) {
		String result;
		try {
			result = observation.get();
		} catch (Throwable t) {
			result = t.getClass().getSimpleName() + ": " + firstLine(t.getMessage());
		}
		System.out.printf("PROBE|%s|%s|%s%n", setup(), name, result);
	}

	private static String firstLine(String s) {
		if (s == null) {
			return "";
		}
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}
}
