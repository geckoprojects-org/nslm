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
package org.example.serviceloader.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * slf4j 2 is the counter example to every other API in this test bundle: it is the
 * one that DOES carry OSGi Service Loader Mediator metadata. {@code slf4j.api}
 * requires {@code osgi.extender=osgi.serviceloader.processor} and
 * {@code osgi.serviceloader=org.slf4j.spi.SLF4JServiceProvider},
 * {@code slf4j.simple} requires {@code osgi.extender=osgi.serviceloader.registrar}
 * and provides the matching {@code osgi.serviceloader} capability. Without a
 * provider of the two extender capabilities neither bundle resolves, so nothing
 * runs at all - the bndruns declare them in {@code -runsystemcapabilities} (the
 * weaver also carries them in its own manifest, see
 * {@code org.example.spi.weaver/bnd.bnd}).
 * <p>
 * What the mediators then have to serve is the hardest of the lookup forms:
 * {@code LoggerFactory.findServiceProviders()} calls
 * {@code ServiceLoader.load(SLF4JServiceProvider.class, LoggerFactory.class.getClassLoader())},
 * that is the two argument form with the API bundle's own class loader, from
 * inside a third party bundle.
 */
@ExtendWith(BundleContextExtension.class)
public class Slf4jServiceLoaderTest {

	static final String API = "slf4j.api";
	static final String PROVIDER = "slf4j.simple";
	static final String PROVIDER_CLASS = "org.slf4j.simple.SimpleServiceProvider";

	@InjectBundleContext
	BundleContext context;

	/**
	 * The metadata is really there and the bundles are resolved anyway, with the
	 * extender requirements wired to the system bundle.
	 */
	@Test
	void mediatorMetadataIsPresentAndTheExtenderRequirementsAreWired() {
		Bundle api = TestSupport.bundle(context, API);
		Bundle provider = TestSupport.bundle(context, PROVIDER);

		assertThat(String.valueOf(api.getHeaders().get("Require-Capability")))
			.contains("osgi.extender=osgi.serviceloader.processor")
			.contains("osgi.serviceloader=org.slf4j.spi.SLF4JServiceProvider");
		assertThat(String.valueOf(provider.getHeaders().get("Require-Capability")))
			.contains("osgi.extender=osgi.serviceloader.registrar");
		assertThat(String.valueOf(provider.getHeaders().get("Provide-Capability")))
			.contains("osgi.serviceloader=\"org.slf4j.spi.SLF4JServiceProvider\"");

		assertThat(extenderProviders(api)).as("who satisfies the processor extender of %s", API)
			.containsExactly(context.getBundle(0).getSymbolicName());
		assertThat(extenderProviders(provider)).as("who satisfies the registrar extender of %s", PROVIDER)
			.containsExactly(context.getBundle(0).getSymbolicName());
	}

	/**
	 * The lookup slf4j itself performs: two argument
	 * {@code ServiceLoader.load(type, apiBundleClassLoader)} from a bundle, which
	 * the weaver serves through the woven call site and the launcher based
	 * mediator through the bundle class loader hook.
	 */
	@Test
	void theProviderIsVisibleThroughTheApiBundlesClassLoader() {
		ClassLoader apiLoader = TestSupport.bundle(context, API).adapt(BundleWiring.class).getClassLoader();

		List<String> providers = new ArrayList<>();
		for (SLF4JServiceProvider provider : ServiceLoader.load(SLF4JServiceProvider.class, apiLoader)) {
			providers.add(provider.getClass().getName());
		}

		assertThat(providers).containsExactly(PROVIDER_CLASS);
	}

	/** and the same lookup done by slf4j itself really binds the provider bundle */
	@Test
	void loggerFactoryBindsTheProviderBundle() {
		ILoggerFactory factory = LoggerFactory.getILoggerFactory();
		assertThat(factory.getClass().getName()).isEqualTo("org.slf4j.simple.SimpleLoggerFactory");

		Logger logger = LoggerFactory.getLogger(Slf4jServiceLoaderTest.class);
		assertThat(logger.getClass().getName()).isEqualTo("org.slf4j.simple.SimpleLogger");
		logger.info("slf4j 2 bound {} in OSGi", PROVIDER_CLASS);
	}

	private static List<String> extenderProviders(Bundle bundle) {
		List<String> names = new ArrayList<>();
		for (BundleWire wire : bundle.adapt(BundleWiring.class).getRequiredWires("osgi.extender")) {
			names.add(wire.getProvider().getBundle().getSymbolicName());
		}
		return names;
	}
}
