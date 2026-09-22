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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.example.serviceloader.api.Greeter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * Only meaningful in the {@code test-weaver*.bndrun} runs (skipped otherwise):
 * the second mediator, {@code org.example.spi.weaver}, is a framework extension
 * bundle whose WeavingHook redirects {@code ServiceLoader.load} call sites to
 * {@code org.example.spi.weaver.ServiceLoaders}. A woven class gets a dynamic
 * import of that package, which is exported by the system bundle.
 */
@ExtendWith(BundleContextExtension.class)
public class WeaverTest {

	static final String WEAVER = "org.example.spi.weaver";
	/** the Java 21 variant, same sources without the call site technique */
	static final String WEAVER_JAVA21 = "org.example.spi.weaver.java21";
	static final String WEAVER_PACKAGE = "org.example.spi.weaver";

	@InjectBundleContext
	BundleContext context;

	Bundle weaver;

	@BeforeEach
	void weaverInstalled() {
		for (Bundle bundle : context.getBundles()) {
			if (WEAVER.equals(bundle.getSymbolicName()) || WEAVER_JAVA21.equals(bundle.getSymbolicName())) {
				weaver = bundle;
			}
		}
		assumeTrue(weaver != null, "weaving mediator not part of this run");
	}

	@Test
	void weaverIsAResolvedFrameworkExtension() {
		assertThat(weaver.getHeaders().get("Fragment-Host")).contains("system.bundle").contains("extension:=framework");
		assertThat(weaver.getState()).isEqualTo(Bundle.RESOLVED);
		assertThat(context.getBundle(0).adapt(BundleWiring.class).getCapabilities(BundleRevision.PACKAGE_NAMESPACE))
			.as("system bundle exports the weaver package")
			.anyMatch(c -> WEAVER_PACKAGE.equals(c.getAttributes().get(BundleRevision.PACKAGE_NAMESPACE)));
	}

	/** the consumer's DS component ran ServiceLoader.load in activate(): its class was woven */
	@Test
	void consumerCallSiteIsWoven() {
		Bundle consumer = TestSupport.bundle(context, GreeterServiceLoaderTest.CONSUMER_V1);
		TestSupport.restartAndCapture(consumer, "[GreeterConsumer] found");

		assertThat(dynamicImportOfWeaverPackage(consumer)).as("dynamic import wire to %s", WEAVER_PACKAGE).isNotNull();
		assertThat(dynamicImportOfWeaverPackage(consumer).getProvider().getBundle().getBundleId()).isZero();
	}

	/** this test bundle calls ServiceLoader.load itself and is woven as well */
	@Test
	void testBundleIsWoven() {
		new GreeterServiceLoaderTest().streamApiWorks();

		assertThat(dynamicImportOfWeaverPackage(context.getBundle())).isNotNull();
	}

	/**
	 * ServiceLoader::load has no call instruction; the weaver redirects the
	 * LambdaMetafactory bootstrap argument and captures the calling class.
	 */
	@Test
	void methodReferenceIsWoven() {
		Function<Class<Greeter>, ServiceLoader<Greeter>> load = ServiceLoader::load;
		BiFunction<Class<Greeter>, ClassLoader, ServiceLoader<Greeter>> loadWith = ServiceLoader::load;

		assertThat(load.apply(Greeter.class).stream().map(p -> p.type().getSimpleName()))
			.containsExactlyInAnyOrder("EnglishGreeter", "GermanGreeter");
		assertThat(loadWith.apply(Greeter.class, getClass().getClassLoader()).stream().map(p -> p.type().getSimpleName()))
			.containsExactlyInAnyOrder("EnglishGreeter", "GermanGreeter");
	}

	/**
	 * Probe: the weaver declares osgi.extender=osgi.serviceloader.processor and
	 * ...registrar in its own manifest, so that bundles which DO carry Service
	 * Loader Mediator metadata (slf4j 2) can resolve. The question is whether a
	 * framework extension's capabilities really become capabilities of the system
	 * bundle at runtime, or whether only -runsystemcapabilities does the job.
	 */
	@Test
	void extenderCapabilitiesOfTheExtensionAreAttachedToTheSystemBundle() {
		List<BundleCapability> extenders = context.getBundle(0).adapt(BundleWiring.class)
			.getCapabilities("osgi.extender");

		assertThat(extenders).filteredOn(c -> c.getRevision().getBundle() == weaver)
			.as("osgi.extender capabilities of %s seen on the system bundle: %s", weaver.getSymbolicName(), extenders)
			.extracting(c -> c.getAttributes().get("osgi.extender"))
			.containsExactlyInAnyOrder("osgi.serviceloader.processor", "osgi.serviceloader.registrar");
	}

	private static BundleWire dynamicImportOfWeaverPackage(Bundle bundle) {
		List<BundleWire> wires = bundle.adapt(BundleWiring.class).getRequiredWires(BundleRevision.PACKAGE_NAMESPACE);
		for (BundleWire wire : wires) {
			if (WEAVER_PACKAGE.equals(wire.getCapability().getAttributes().get(BundleRevision.PACKAGE_NAMESPACE))) {
				return wire;
			}
		}
		return null;
	}
}
