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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * The typical ways a {@code ServiceLoader} call gets separated from the bundle
 * that makes it: unusual call forms and unusual thread contexts.
 * <p>
 * The caller bundle {@code org.example.serviceloader.pitfalls} is wired to
 * Greeter 2.0, this test bundle to Greeter 1.0. The only correct answer is
 * therefore the 2.0 provider {@code FrenchGreeter}; {@code EnglishGreeter} /
 * {@code GermanGreeter} or a {@code "not a subtype"} error mean that the lookup
 * ran in the class space of the test bundle (the thread context, not the
 * caller), an empty list means that it was not mediated at all.
 * <p>
 * The caller bundle is not in any {@code -runbundles}; the test installs it
 * from {@code embedded/} inside the test bundle.
 * <p>
 * An edge case of the weaver is covered as well: a {@code MethodHandle} or
 * reflective call does not appear in the constant pool and is not woven; it
 * depends on the TCCL, with the weaver only through {@code spi.weaver.tccl=true},
 * so these call forms also run on threads with a missing or foreign TCCL. The
 * ServiceLoader calls inside the JDK are the subject of {@link JdkFactoryPitfallsTest}.
 */
@ExtendWith(BundleContextExtension.class)
public class TcclPitfallsTest {

	static final String CALLER = "org.example.serviceloader.pitfalls";
	static final List<String> CORRECT = List.of("org.example.serviceloader.provider.v2.FrenchGreeter");

	@InjectBundleContext
	BundleContext context;

	private Bundle caller;

	@BeforeEach
	void install() throws IOException, BundleException {
		try (InputStream in = TcclPitfallsTest.class.getResourceAsStream("/embedded/" + CALLER + ".jar")) {
			assertThat(in).as("embedded/%s.jar in the test bundle", CALLER).isNotNull();
			caller = context.installBundle("pitfalls:" + CALLER, in);
		}
		caller.start();
	}

	@AfterEach
	void uninstall() throws BundleException {
		caller.uninstall();
	}

	/** call forms that hide or add frames between the caller and ServiceLoader */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"direct", "lambda", "methodReference", "methodReferenceAppliedByJdk", "methodHandle", "reflection",
		"explicitOwnLoader", "explicitApiLoader"
	})
	void callStyle(String style) {
		assertThat(report("style " + style, callStyles().apply(style))).isEqualTo(CORRECT);
	}

	/** baseline: the caller's lookup on the test thread, TCCL as the launcher left it */
	@Test
	void onTheTestThread() throws Exception {
		assertThat(report("test thread", lookup().call())).isEqualTo(CORRECT);
	}

	/**
	 * {@code CompletableFuture.supplyAsync}: common pool threads are created by
	 * the JDK with the system class loader as TCCL.
	 */
	@Test
	void commonPool() {
		Callable<List<String>> lookup = lookup();
		List<String> result = CompletableFuture.supplyAsync(() -> call(lookup)).join();
		assertThat(report("common pool", result)).isEqualTo(CORRECT);
	}

	/** a thread whose TCCL is {@code null}: the JDK falls back to the system class loader */
	@Test
	void threadWithoutTccl() throws InterruptedException {
		Callable<List<String>> lookup = lookup();
		AtomicReference<List<String>> result = new AtomicReference<>();
		Thread thread = new Thread(() -> result.set(call(lookup)), "no-tccl");
		thread.setContextClassLoader(null);
		thread.start();
		thread.join(10_000);
		assertThat(report("thread without TCCL", result.get())).isEqualTo(CORRECT);
	}

	/**
	 * A pool thread created while another bundle's class loader (the test
	 * bundle's, Greeter 1.0) was the TCCL keeps it for every later task: the
	 * reused thread of a pool or event loop, the inherited TCCL of a child thread.
	 */
	@Test
	void poolThreadWithStaleTccl() throws Exception {
		Callable<List<String>> lookup = lookup();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			withTccl(TcclPitfallsTest.class.getClassLoader(), () -> pool.submit(() -> {
			}).get(10, TimeUnit.SECONDS));
			assertThat(report("pool thread with stale TCCL", pool.submit(lookup).get(10, TimeUnit.SECONDS)))
				.isEqualTo(CORRECT);
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * A foreign TCCL left on the calling thread: a callback of another framework
	 * that set its own loader, or a {@code setContextClassLoader} without
	 * {@code finally}.
	 */
	@Test
	void foreignTcclNotRestored() throws Exception {
		Callable<List<String>> lookup = lookup();
		List<String> result = withTccl(TcclPitfallsTest.class.getClassLoader(), lookup);
		assertThat(report("foreign TCCL", result)).isEqualTo(CORRECT);
	}

	/**
	 * The call forms on a pool thread with a stale foreign TCCL (the test
	 * bundle's, Greeter 1.0): {@code direct} is woven, {@code methodHandle} and
	 * {@code reflection} are not.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"direct", "methodHandle", "reflection"
	})
	void callStyleInPoolThreadWithStaleTccl(String style) throws Exception {
		Function<String, List<String>> styles = callStyles();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			withTccl(TcclPitfallsTest.class.getClassLoader(), () -> pool.submit(() -> {
			}).get(10, TimeUnit.SECONDS));
			List<String> result = pool.submit(() -> styles.apply(style)).get(10, TimeUnit.SECONDS);
			assertThat(report("style " + style + " in pool thread with stale TCCL", result)).isEqualTo(CORRECT);
		} finally {
			pool.shutdownNow();
		}
	}

	/** the call forms in the common pool, whose threads have the system class loader as TCCL */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"direct", "methodHandle", "reflection"
	})
	void callStyleInCommonPool(String style) {
		Function<String, List<String>> styles = callStyles();
		List<String> result = CompletableFuture.supplyAsync(() -> styles.apply(style)).join();
		assertThat(report("style " + style + " in common pool", result)).isEqualTo(CORRECT);
	}

	private List<String> report(String scenario, List<String> result) {
		System.out.println("[TcclPitfallsTest] " + context.getProperty("test.deployment") + ", " + scenario + ": " + result);
		return result;
	}

	private static <T> T withTccl(ClassLoader tccl, Callable<T> action) throws Exception {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(tccl);
		try {
			return action.call();
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	private static List<String> call(Callable<List<String>> lookup) {
		try {
			return lookup.call();
		} catch (Exception e) {
			return List.of("error: " + e);
		}
	}

	@SuppressWarnings("unchecked")
	private Callable<List<String>> lookup() {
		return service(Callable.class, "(pitfall=lookup)");
	}

	@SuppressWarnings("unchecked")
	private Function<String, List<String>> callStyles() {
		return service(Function.class, "(pitfall=styles)");
	}

	@SuppressWarnings({
		"rawtypes", "unchecked"
	})
	private <S> S service(Class<S> type, String filter) {
		TestSupport.await(() -> references(type, filter).length > 0);
		ServiceReference[] refs = references(type, filter);
		assertThat(refs).as("%s %s of %s", type.getSimpleName(), filter, CALLER).hasSize(1);
		return (S) context.getService(refs[0]);
	}

	private ServiceReference<?>[] references(Class<?> type, String filter) {
		try {
			ServiceReference<?>[] refs = context.getServiceReferences(type.getName(), filter);
			return refs == null ? new ServiceReference<?>[0] : refs;
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}
}
