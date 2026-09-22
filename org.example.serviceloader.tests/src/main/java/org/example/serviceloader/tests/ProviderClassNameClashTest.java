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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * Two provider bundles ship a provider class of the SAME name,
 * {@code org.example.serviceloader.clash.ClashGreeter}, one implementing Greeter
 * 1.0, the other Greeter 2.0. Two caller bundles, one wired to each API version,
 * call {@code ServiceLoader.load(Greeter.class)} without a class loader argument,
 * so the JDK asks the thread context class loader and loads the provider class
 * with {@code Class.forName(name, false, tccl)}.
 * <p>
 * The JVM records the TCCL as initiating loader of the class it returned and
 * answers every later {@code Class.forName} of that name with that loader from its
 * own cache, without asking the loader again (osgi/osgi#970, comment by Tom
 * Watson). A TCCL shared by all bundles (the launcher's {@code SpiClassLoader} on
 * Felix, the {@code ContextFinder} on Equinox) therefore hands the first caller's
 * class to the second caller, and keeps handing out the class of an uninstalled
 * bundle. The caller's own bundle class loader as TCCL does not have that problem:
 * every bundle has its own loader, and a new one after reinstall.
 * <p>
 * The four bundles are not in any {@code -runbundles}; the test installs them
 * from {@code clash/} inside the test bundle and uninstalls them afterwards.
 */
@ExtendWith(BundleContextExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProviderClassNameClashTest {

	static final String CLASH = "org.example.serviceloader.clash.ClashGreeter";
	static final String PROVIDER_V1 = "org.example.serviceloader.clash.provider";
	static final String PROVIDER_V2 = "org.example.serviceloader.clash.provider.v2";
	static final List<String> BUNDLES = List.of(PROVIDER_V1, PROVIDER_V2, "org.example.serviceloader.clash.caller",
		"org.example.serviceloader.clash.caller.v2");

	/** the caller keeps the TCCL the test thread has */
	static final String INHERITED = "inherited";
	/** the caller sets its own bundle class loader as TCCL */
	static final String BUNDLE = "bundle";

	@InjectBundleContext
	BundleContext context;

	private final List<Bundle> installed = new ArrayList<>();

	@BeforeEach
	void install() throws IOException, BundleException {
		installAll();
	}

	@AfterEach
	void uninstall() throws BundleException, InterruptedException {
		uninstallAll();
	}

	@Test
	@Order(1)
	void ownBundleClassLoaderAsTccl() throws InvalidSyntaxException {
		assertThat(lookup("2.0", BUNDLE)).containsExactly(expected(PROVIDER_V2));
		assertThat(lookup("1.0", BUNDLE)).containsExactly(expected(PROVIDER_V1));
	}

	@Test
	@Order(2)
	void inheritedTccl() throws InvalidSyntaxException {
		assertThat(lookup("2.0", INHERITED)).containsExactly(expected(PROVIDER_V2));
		assertThat(lookup("1.0", INHERITED)).containsExactly(expected(PROVIDER_V1));
	}

	@Test
	@Order(3)
	void ownBundleClassLoaderAsTcclAfterReinstall() throws Exception {
		lookup("2.0", BUNDLE);
		uninstallAll();
		installAll();
		assertThat(lookup("2.0", BUNDLE)).containsExactly(expected(PROVIDER_V2));
	}

	@Test
	@Order(4)
	void inheritedTcclAfterReinstall() throws Exception {
		lookup("2.0", INHERITED);
		uninstallAll();
		installAll();
		assertThat(lookup("2.0", INHERITED)).containsExactly(expected(PROVIDER_V2));
	}

	/**
	 * Only the provider is replaced, the caller keeps its wiring: nothing refreshes
	 * the caller, so whatever loader was the initiating loader of the provider class
	 * must not hand out the class of the uninstalled provider.
	 */
	@Test
	@Order(5)
	void ownBundleClassLoaderAsTcclAfterProviderReinstall() throws Exception {
		lookup("2.0", BUNDLE);
		reinstall(PROVIDER_V2);
		assertThat(lookup("2.0", BUNDLE)).containsExactly(expected(PROVIDER_V2));
	}

	@Test
	@Order(6)
	void inheritedTcclAfterProviderReinstall() throws Exception {
		lookup("2.0", INHERITED);
		reinstall(PROVIDER_V2);
		assertThat(lookup("2.0", INHERITED)).containsExactly(expected(PROVIDER_V2));
	}

	/** the line a caller reports for the clash provider of the currently installed bundle */
	private String expected(String bsn) {
		Bundle bundle = installed.stream()
			.filter(b -> bsn.equals(b.getSymbolicName()))
			.findFirst()
			.orElseThrow();
		return CLASH + "|" + bsn + "|" + bundle.getBundleId() + "|" + Bundle.ACTIVE;
	}

	/** the clash provider lines and errors reported by the caller for the API version */
	@SuppressWarnings({
		"rawtypes", "unchecked"
	})
	private List<String> lookup(String api, String tccl) throws InvalidSyntaxException {
		String filter = "(clash.api=" + api + ")";
		TestSupport.await(() -> {
			try {
				return !context.getServiceReferences(Function.class, filter).isEmpty();
			} catch (InvalidSyntaxException e) {
				throw new IllegalArgumentException(e);
			}
		});
		Collection<ServiceReference<Function>> refs = context.getServiceReferences(Function.class, filter);
		assertThat(refs).as("caller for Greeter %s", api).hasSize(1);
		ServiceReference<Function> ref = refs.iterator().next();
		try {
			List<String> lines = (List<String>) context.getService(ref).apply(tccl);
			List<String> result = lines.stream()
				.filter(l -> l.startsWith(CLASH) || l.startsWith("ServiceConfigurationError"))
				.toList();
			System.out.println("[ProviderClassNameClashTest] Greeter " + api + ", " + tccl + " TCCL: " + result);
			return result;
		} finally {
			context.ungetService(ref);
		}
	}

	private void installAll() throws IOException, BundleException {
		for (String bsn : BUNDLES) {
			try (InputStream in = ProviderClassNameClashTest.class.getResourceAsStream("/clash/" + bsn + ".jar")) {
				assertThat(in).as("clash/%s.jar in the test bundle", bsn).isNotNull();
				installed.add(context.installBundle("clash:" + bsn, in));
			}
		}
		for (Bundle bundle : installed) {
			bundle.start();
		}
	}

	private void uninstallAll() throws BundleException, InterruptedException {
		for (Bundle bundle : installed) {
			bundle.uninstall();
		}
		refresh(List.copyOf(installed));
		installed.clear();
	}

	/** uninstall, refresh and install again one of the bundles, the others stay as they are */
	private void reinstall(String bsn) throws IOException, BundleException, InterruptedException {
		Bundle old = installed.stream().filter(b -> bsn.equals(b.getSymbolicName())).findFirst().orElseThrow();
		old.uninstall();
		refresh(List.of(old));
		installed.remove(old);
		try (InputStream in = ProviderClassNameClashTest.class.getResourceAsStream("/clash/" + bsn + ".jar")) {
			Bundle bundle = context.installBundle("clash:" + bsn, in);
			installed.add(bundle);
			bundle.start();
		}
	}

	private void refresh(Collection<Bundle> bundles) throws InterruptedException {
		CountDownLatch refreshed = new CountDownLatch(1);
		context.getBundle(0)
			.adapt(FrameworkWiring.class)
			.refreshBundles(bundles, event -> {
				if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED) {
					refreshed.countDown();
				}
			});
		assertThat(refreshed.await(10, TimeUnit.SECONDS)).as("refresh").isTrue();
	}
}
