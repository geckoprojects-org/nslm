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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.example.serviceloader.api.Greeter;

/**
 * The measured loops, shared by the OSGi runs and the plain classpath baseline.
 * Every scenario is a fresh {@code ServiceLoader} per operation, which is how
 * application code uses it and where a mediator adds its cost. Results are
 * printed as {@code BENCH|<setup>|<scenario>|<us per op>|<providers seen>} so
 * they can be collected from the build log.
 */
public final class Bench {

	/** ServiceLoader.load(Class): the call sites a weaver rewrites */
	public static final Supplier<ServiceLoader<Greeter>> LOAD = () -> ServiceLoader.load(Greeter.class);
	/** ServiceLoader.load(Class, ClassLoader) with the bundle (or app) class loader of this class */
	public static final Supplier<ServiceLoader<Greeter>> LOAD_WITH_LOADER = () -> ServiceLoader.load(Greeter.class,
		Bench.class.getClassLoader());

	private Bench() {
	}

	public static int count(ServiceLoader<Greeter> loader) {
		int n = 0;
		for (Greeter greeter : loader) {
			if (greeter != null) {
				n++;
			}
		}
		return n;
	}

	/** provider types only: classes are loaded, nothing is instantiated */
	public static int countTypes(ServiceLoader<Greeter> loader) {
		return (int) loader.stream().map(ServiceLoader.Provider::type).count();
	}

	/**
	 * @return microseconds per operation, after warm up; also fills {@code seen}
	 *         with the provider count of the last operation
	 */
	public static double measure(Supplier<ServiceLoader<Greeter>> factory, ToIntFunction<ServiceLoader<Greeter>> use,
		int warmup, int iterations, int[] seen) {
		int sink = 0;
		for (int i = 0; i < warmup; i++) {
			sink += use.applyAsInt(factory.get());
		}
		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			sink += use.applyAsInt(factory.get());
		}
		long nanos = System.nanoTime() - start;
		seen[0] = use.applyAsInt(factory.get());
		if (sink == 42) {
			System.out.print("");
		}
		return nanos / 1000.0 / iterations;
	}

	public static Map<String, double[]> run(int warmup, int iterations) {
		Map<String, double[]> results = new LinkedHashMap<>();
		int[] seen = new int[1];
		results.put("load only", new double[] { measure(LOAD, l -> 1, warmup, iterations, seen), 0 });
		results.put("load + iterate", new double[] { measure(LOAD, Bench::count, warmup, iterations, seen), seen[0] });
		results.put("load + stream types", new double[] { measure(LOAD, Bench::countTypes, warmup, iterations, seen), seen[0] });
		results.put("load(cl) + iterate", new double[] { measure(LOAD_WITH_LOADER, Bench::count, warmup, iterations, seen), seen[0] });
		return results;
	}

	public static void report(String setup, Map<String, double[]> results) {
		for (Map.Entry<String, double[]> e : results.entrySet()) {
			System.out.printf("BENCH|%s|%s|%.2f|%d%n", setup, e.getKey(), e.getValue()[0], (int) e.getValue()[1]);
		}
	}

	public static int iterations() {
		return Integer.getInteger("bench.iterations", 50_000);
	}

	public static int warmup() {
		return Integer.getInteger("bench.warmup", 10_000);
	}
}
