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
package org.example.spi.weaver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.spi.core.Tracing;
import org.osgi.framework.Bundle;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Redirects the two static {@code java.util.ServiceLoader.load} methods to
 * {@link ServiceLoaders}, with one of two techniques.
 * <p>
 * <b>{@code cpool}</b> (default, {@link ConstantPoolPatcher}): the MethodRef
 * entries {@code java/util/ServiceLoader.load:(Class)ServiceLoader} and
 * {@code load:(Class, ClassLoader)ServiceLoader} get a new owner class entry
 * {@code org/example/spi/weaver/ServiceLoaders}, appended to the constant pool.
 * Instructions, stack maps and descriptors stay as they are, the return type
 * is still {@code java.util.ServiceLoader}, and a method reference
 * {@code ServiceLoader::load} is covered because its method handle constant
 * points to the same entry. {@link ServiceLoaders} finds the calling class on
 * the stack (the frame directly below it).
 * <p>
 * <b>{@code callsite}</b> ({@link ClassWeaver} {@code CallSiteWeaver}, Class-File
 * API, Java 24+): the call instructions are rewritten to pass the calling class
 * as a constant. Loaded by name on request; the Java 21 variant
 * {@code org.example.spi.weaver.java21} does not contain it, and the hook falls
 * back to {@code cpool}.
 * <p>
 * Nothing else is touched: no other descriptor, no {@code loadInstalled} or
 * {@code load(ModuleLayer, Class)}. Two cheap pre filters (the UTF8 string
 * {@code java/util/ServiceLoader} in the raw bytes, then a MethodRef in the
 * constant pool) keep the cost for the vast majority of classes near zero.
 * Woven classes get a {@code DynamicImport-Package} on the exported
 * {@link ServiceLoaders} package.
 * <p>
 * Any failure leaves the class unwoven; the hook never throws, because a
 * throwing WeavingHook is blacklisted by the framework.
 */
final class ServiceLoaderWeavingHook implements WeavingHook {

	static final String SERVICE_LOADER = "java/util/ServiceLoader";
	static final String DYNAMIC_IMPORT = ServiceLoaders.class.getPackageName() + ";version=\"[1.0,2)\"";

	private static final byte[] MARKER = SERVICE_LOADER.getBytes(StandardCharsets.ISO_8859_1);
	private static final String SERVICE_LOADERS = ServiceLoaders.class.getName().replace('.', '/');
	private static final Set<String> LOAD_DESCRIPTORS = Set.of("(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
		"(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;");
	private static final String CALL_SITE_WEAVER = ServiceLoaders.class.getPackageName() + ".CallSiteWeaver";

	/** how the calls are redirected, see the class comment */
	enum Technique {
		CPOOL, CALLSITE;

		/** @return the technique named by {@code value}, {@link #CPOOL} if {@code null} */
		static Technique of(String value) {
			return value == null || value.isBlank() ? CPOOL : valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
	}

	private final Tracing trace;
	private final Technique technique;
	private final ClassWeaver weaver;
	private final AtomicInteger wovenClasses = new AtomicInteger();
	private final AtomicInteger wovenCallSites = new AtomicInteger();

	ServiceLoaderWeavingHook(Tracing trace, Technique requested) {
		this.trace = trace;
		ClassWeaver callSite = requested == Technique.CALLSITE ? callSiteWeaver() : null;
		this.technique = callSite != null ? Technique.CALLSITE : Technique.CPOOL;
		this.weaver = callSite != null ? callSite : this::redirectInConstantPool;
	}

	/** the technique in use, {@link Technique#CPOOL} if {@code callsite} is not available */
	Technique technique() {
		return technique;
	}

	/** number of classes changed so far */
	public int wovenClasses() {
		return wovenClasses.get();
	}

	/** number of call sites (callsite) or method references (cpool) changed so far */
	public int wovenCallSites() {
		return wovenCallSites.get();
	}

	@Override
	public void weave(WovenClass wovenClass) {
		byte[] bytes = wovenClass.getBytes();
		if (indexOf(bytes, MARKER) < 0) {
			return;
		}
		try {
			byte[] woven = weaver.weave(wovenClass.getClassName(), bytes, wovenClass.getBundleWiring(), wovenCallSites);
			if (woven != null) {
				wovenClass.setBytes(woven);
				List<String> imports = wovenClass.getDynamicImports();
				if (!imports.contains(DYNAMIC_IMPORT)) {
					imports.add(DYNAMIC_IMPORT);
				}
				wovenClasses.incrementAndGet();
			}
		} catch (RuntimeException | LinkageError e) {
			Bundle bundle = wovenClass.getBundleWiring().getBundle();
			trace.trace("cannot weave %s of bundle %s: %s", wovenClass.getClassName(), bundle.getSymbolicName(), e);
		}
	}

	/** the {@code cpool} technique, see {@link ConstantPoolPatcher} */
	byte[] redirectInConstantPool(String className, byte[] bytes, BundleWiring wiring, AtomicInteger changes) {
		ConstantPoolPatcher.Result result = ConstantPoolPatcher.redirect(bytes, SERVICE_LOADER, "load",
			LOAD_DESCRIPTORS, SERVICE_LOADERS);
		if (result == null) {
			return null;
		}
		changes.addAndGet(result.redirected());
		trace.trace("woven %s: %d ServiceLoader.load method reference(s) in the constant pool%s", className,
			result.redirected(), wiring == null ? "" : " of bundle " + wiring.getBundle().getSymbolicName());
		return result.bytes();
	}

	/**
	 * {@code CallSiteWeaver} uses the Class-File API: missing in the Java 21
	 * variant of the weaver, and a Java 25 class file that an older JVM rejects
	 * with an {@link UnsupportedClassVersionError}.
	 *
	 * @return the call site technique, or {@code null} if it is not available
	 */
	private ClassWeaver callSiteWeaver() {
		try {
			return (ClassWeaver) ServiceLoaderWeavingHook.class.getClassLoader()
				.loadClass(CALL_SITE_WEAVER)
				.getDeclaredConstructor(Tracing.class)
				.newInstance(trace);
		} catch (ReflectiveOperationException | LinkageError e) {
			trace.trace("technique callsite not available (Java %d, %s), using cpool", Runtime.version().feature(), e);
			return null;
		}
	}

	static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0, max = haystack.length - needle.length; i <= max; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
