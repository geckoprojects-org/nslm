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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * ServiceLoader calls inside the JDK, which no weaver can reach: the StAX and
 * JAXP factories ({@code javax.xml.stream.FactoryFinder},
 * {@code javax.xml.parsers.FactoryFinder}) ask the thread context class loader
 * for {@code META-INF/services/<factory>}. The providers are bundles: Woodstox
 * for StAX, {@code org.example.serviceloader.jaxp.provider} (installed by this
 * test from {@code embedded/}) for DOM and SAX.
 * <p>
 * The lookup runs on the test thread, in the common pool (system class loader
 * as TCCL) and on a thread without TCCL. The weaver redirects the factory calls
 * of bundle classes to {@code org.example.spi.weaver.JdkFactories}, which sets
 * the calling bundle's loader as TCCL for the duration of the call; the other
 * deployments depend on the TCCL the thread happens to have.
 */
@ExtendWith(BundleContextExtension.class)
public class JdkFactoryPitfallsTest {

	static final String PROVIDER = "org.example.serviceloader.jaxp.provider";

	@InjectBundleContext
	BundleContext context;

	private Bundle provider;

	@BeforeEach
	void install() throws IOException, BundleException {
		try (InputStream in = JdkFactoryPitfallsTest.class.getResourceAsStream("/embedded/" + PROVIDER + ".jar")) {
			assertThat(in).as("embedded/%s.jar in the test bundle", PROVIDER).isNotNull();
			provider = context.installBundle("jaxp:" + PROVIDER, in);
		}
		provider.start();
	}

	@AfterEach
	void uninstall() throws BundleException {
		provider.uninstall();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"stax", "dom", "sax"
	})
	void onTheTestThread(String factory) {
		assertThat(report(factory, "test thread", create(factory))).isEqualTo(expected(factory));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"stax", "dom", "sax"
	})
	void inCommonPool(String factory) {
		String result = CompletableFuture.supplyAsync(() -> create(factory)).join();
		assertThat(report(factory, "common pool", result)).isEqualTo(expected(factory));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"stax", "dom", "sax"
	})
	void onThreadWithoutTccl(String factory) throws InterruptedException {
		AtomicReference<String> result = new AtomicReference<>();
		Thread thread = new Thread(() -> result.set(create(factory)), "no-tccl");
		thread.setContextClassLoader(null);
		thread.start();
		thread.join(10_000);
		assertThat(report(factory, "thread without TCCL", result.get())).isEqualTo(expected(factory));
	}

	private static String expected(String factory) {
		return switch (factory) {
			case "stax" -> "com.ctc.wstx.stax.WstxInputFactory";
			case "dom" -> "org.example.serviceloader.jaxp.ExampleDocumentBuilderFactory";
			case "sax" -> "org.example.serviceloader.jaxp.ExampleSAXParserFactory";
			default -> throw new IllegalArgumentException(factory);
		};
	}

	/** the factory calls are in this bundle class, where the weaver can redirect them */
	private static String create(String factory) {
		try {
			return switch (factory) {
				case "stax" -> XMLInputFactory.newInstance().getClass().getName();
				case "dom" -> DocumentBuilderFactory.newInstance().getClass().getName();
				case "sax" -> SAXParserFactory.newInstance().getClass().getName();
				default -> throw new IllegalArgumentException(factory);
			};
		} catch (RuntimeException | Error e) {
			return "error: " + e;
		}
	}

	private String report(String factory, String scenario, String result) {
		System.out.println("[JdkFactoryPitfallsTest] " + context.getProperty("test.deployment") + ", " + factory + " on "
			+ scenario + ": [" + result + "]");
		return result;
	}
}
