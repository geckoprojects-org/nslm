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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.example.serviceloader.api.Greeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * Plain bundles, plain {@code java.util.ServiceLoader}: providers have
 * META-INF/services only, consumers call {@code ServiceLoader.load} in the
 * {@code activate()} of a DS component. No OSGi Service Loader Mediator
 * metadata, no module-info. Two versions of the API package are installed
 * (Greeter 1.0 in {@code org.example.serviceloader.api}, Greeter 2.0 in
 * {@code org.example.serviceloader.api.v2}); each side must only see its own
 * class space.
 */
@ExtendWith(BundleContextExtension.class)
public class GreeterServiceLoaderTest {

	static final String PROVIDER_V1 = "org.example.serviceloader.provider";
	static final String CONSUMER_V1 = "org.example.serviceloader.consumer";
	static final String PROVIDER_V2 = "org.example.serviceloader.provider.v2";
	static final String CONSUMER_V2 = "org.example.serviceloader.consumer.v2";

	static final String ENGLISH = "org.example.serviceloader.provider.EnglishGreeter: Hello from the English greeter";
	static final String GERMAN = "org.example.serviceloader.provider.GermanGreeter: Hallo vom deutschen Greeter";
	static final String FRENCH = "org.example.serviceloader.provider.v2.FrenchGreeter (fr): Bonjour du greeter français";

	@InjectBundleContext
	BundleContext context;

	@Test
	void bundlesCarryNoServiceLoaderMetadata() {
		for (String bsn : new String[] { PROVIDER_V1, CONSUMER_V1, PROVIDER_V2, CONSUMER_V2 }) {
			Bundle bundle = TestSupport.bundle(context, bsn);
			String provide = String.valueOf(bundle.getHeaders().get("Provide-Capability"));
			String require = String.valueOf(bundle.getHeaders().get("Require-Capability"));
			assertThat(provide).as("%s Provide-Capability", bsn).doesNotContain("osgi.serviceloader");
			assertThat(require).as("%s Require-Capability", bsn).doesNotContain("osgi.serviceloader").doesNotContain("osgi.extender=osgi.serviceloader");
		}
	}

	@Test
	void consumerOfApi10SeesOnlyProvidersOfApi10() {
		String output = TestSupport.restartAndCapture(TestSupport.bundle(context, CONSUMER_V1), "[GreeterConsumer] found");

		assertThat(output).contains(ENGLISH).contains(GERMAN)
			.contains("[GreeterConsumer] found 2 Greeter provider(s)")
			.doesNotContain("FrenchGreeter");
	}

	@Test
	void consumerOfApi20SeesOnlyProvidersOfApi20() {
		String output = TestSupport.restartAndCapture(TestSupport.bundle(context, CONSUMER_V2), "[GreeterConsumerV2] found");

		assertThat(output).contains(FRENCH)
			.contains("[GreeterConsumerV2] found 1 Greeter 2.0 provider(s)")
			.doesNotContain("EnglishGreeter").doesNotContain("GermanGreeter");
	}

	/** this test bundle is wired to Greeter 1.0; TCCL path (no class loader argument) */
	@Test
	void serviceLoaderWithoutClassLoaderArgument() {
		assertThat(greetings(ServiceLoader.load(Greeter.class)))
			.containsExactlyInAnyOrder("Hello from the English greeter", "Hallo vom deutschen Greeter");
	}

	/** explicit bundle class loader: served by the bundle class loader hook (Felix map / Equinox hook) */
	@Test
	void serviceLoaderWithBundleClassLoaderArgument() {
		ServiceLoader<Greeter> loader = ServiceLoader.load(Greeter.class, GreeterServiceLoaderTest.class.getClassLoader());

		assertThat(greetings(loader))
			.containsExactlyInAnyOrder("Hello from the English greeter", "Hallo vom deutschen Greeter");
	}

	/** stream() works because both mediators hand out the real java.util.ServiceLoader */
	@Test
	void streamApiWorks() {
		List<String> names = ServiceLoader.load(Greeter.class).stream().map(p -> p.type().getName()).toList();

		assertThat(names).containsExactlyInAnyOrder(
			"org.example.serviceloader.provider.EnglishGreeter",
			"org.example.serviceloader.provider.GermanGreeter");
	}

	/**
	 * Like packages, providers exist from RESOLVED on: stopping a provider bundle
	 * neither removes its providers nor makes the mediator start it again;
	 * uninstalling removes them.
	 */
	@Test
	void providerLifecycle() throws BundleException {
		Bundle provider = TestSupport.bundle(context, PROVIDER_V1);
		String location = provider.getLocation();
		try {
			provider.stop();
			assertThat(greetings(ServiceLoader.load(Greeter.class))).as("stopped provider bundle").hasSize(2);
			assertThat(provider.getState()).as("mediator must not start the bundle").isEqualTo(Bundle.RESOLVED);

			provider.uninstall();
			TestSupport.await(() -> greetings(ServiceLoader.load(Greeter.class)).isEmpty());
			assertThat(greetings(ServiceLoader.load(Greeter.class))).as("uninstalled provider bundle").isEmpty();
		} finally {
			if (provider.getState() == Bundle.UNINSTALLED) {
				// the injected context is scoped by osgi-test and would uninstall the bundle
				// again when the test ends; use the test bundle's real context
				provider = context.getBundle().getBundleContext().installBundle(location);
			}
			provider.start();
		}
		TestSupport.await(() -> greetings(ServiceLoader.load(Greeter.class)).size() == 2);
		assertThat(greetings(ServiceLoader.load(Greeter.class))).hasSize(2);
	}

	/**
	 * A bundle that falls back to INSTALLED exposes no packages any more, hence no
	 * providers either; resolving it brings them back. {@code update()} unresolves
	 * the bundle in both frameworks (a refresh would already re-resolve it on Equinox).
	 */
	@Test
	void unresolvedProviderBundleLosesItsProviders() throws Exception {
		Bundle provider = TestSupport.bundle(context, PROVIDER_V1);
		FrameworkWiring frameworkWiring = context.getBundle(0).adapt(FrameworkWiring.class);
		try {
			provider.stop();
			provider.update();
			assertThat(provider.getState()).as("updated bundle is unresolved").isEqualTo(Bundle.INSTALLED);
			TestSupport.await(() -> greetings(ServiceLoader.load(Greeter.class)).isEmpty());
			assertThat(greetings(ServiceLoader.load(Greeter.class))).as("providers of an INSTALLED bundle").isEmpty();

			assertThat(frameworkWiring.resolveBundles(List.of(provider))).isTrue();
			TestSupport.await(() -> greetings(ServiceLoader.load(Greeter.class)).size() == 2);
			assertThat(greetings(ServiceLoader.load(Greeter.class))).as("providers after resolve").hasSize(2);
		} finally {
			CountDownLatch refreshed = new CountDownLatch(1);
			frameworkWiring.refreshBundles(List.of(provider), event -> refreshed.countDown());
			refreshed.await(30, TimeUnit.SECONDS);
			provider.start();
			TestSupport.await(() -> greetings(ServiceLoader.load(Greeter.class)).size() == 2);
		}
	}

	private static List<String> greetings(ServiceLoader<Greeter> loader) {
		List<String> greetings = new ArrayList<>();
		for (Greeter greeter : loader) {
			greetings.add(greeter.greet());
		}
		return greetings;
	}
}
