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

import static java.lang.constant.ConstantDescs.CD_Class;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandleInfo;
import java.util.ArrayList;
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
 * <b>{@code callsite}</b> (Class-File API, {@code java.lang.classfile}, Java 24+):
 * <pre>
 * invokestatic java/util/ServiceLoader.load:(Class)ServiceLoader
 *   ->  ldc ThisClass
 *       invokestatic org/example/spi/weaver/ServiceLoaders.load:(Class, Class)ServiceLoader
 * invokestatic java/util/ServiceLoader.load:(Class, ClassLoader)ServiceLoader
 *   ->  ldc ThisClass
 *       invokestatic org/example/spi/weaver/ServiceLoaders.load:(Class, ClassLoader, Class)ServiceLoader
 * </pre>
 * A method reference {@code ServiceLoader::load} compiles to an
 * {@code invokedynamic} whose {@code LambdaMetafactory} bootstrap argument is a
 * method handle to {@code ServiceLoader.load}; there is no call instruction to
 * rewrite. For call sites without captured arguments (the normal case for a
 * reference to a static method) the handle is redirected to
 * {@code ServiceLoaders.loadFrom(Class caller, ...)} and the calling class is
 * pushed as a captured argument of the lambda:
 * <pre>
 * invokedynamic apply()Function  [.., MH ServiceLoader.load(Class), ..]
 *   ->  ldc ThisClass
 *       invokedynamic apply(Class)Function  [.., MH ServiceLoaders.loadFrom(Class, Class), ..]
 * </pre>
 * Stack maps of changed methods are regenerated.
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
	private static final ClassDesc CD_ServiceLoader = ClassDesc.of("java.util.ServiceLoader");
	private static final ClassDesc CD_ClassLoader = ClassDesc.of("java.lang.ClassLoader");
	private static final ClassDesc CD_ServiceLoaders = ClassDesc.of(ServiceLoaders.class.getName());
	private static final MethodTypeDesc LOAD_TYPE = MethodTypeDesc.of(CD_ServiceLoader, CD_Class);
	private static final MethodTypeDesc LOAD_TYPE_LOADER = MethodTypeDesc.of(CD_ServiceLoader, CD_Class, CD_ClassLoader);
	private static final MethodTypeDesc WOVEN_TYPE = MethodTypeDesc.of(CD_ServiceLoader, CD_Class, CD_Class);
	private static final MethodTypeDesc WOVEN_TYPE_LOADER = MethodTypeDesc.of(CD_ServiceLoader, CD_Class, CD_ClassLoader,
		CD_Class);
	private static final DirectMethodHandleDesc MH_LOAD_FROM = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
		CD_ServiceLoaders, "loadFrom", MethodTypeDesc.of(CD_ServiceLoader, CD_Class, CD_Class));
	private static final DirectMethodHandleDesc MH_LOAD_FROM_LOADER = MethodHandleDesc.ofMethod(
		DirectMethodHandleDesc.Kind.STATIC, CD_ServiceLoaders, "loadFrom",
		MethodTypeDesc.of(CD_ServiceLoader, CD_Class, CD_Class, CD_ClassLoader));
	private static final String LAMBDA_METAFACTORY = "Ljava/lang/invoke/LambdaMetafactory;";
	private static final String SERVICE_LOADERS = ServiceLoaders.class.getName().replace('.', '/');
	private static final Set<String> LOAD_DESCRIPTORS = Set.of(LOAD_TYPE.descriptorString(),
		LOAD_TYPE_LOADER.descriptorString());

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
	private final AtomicInteger wovenClasses = new AtomicInteger();
	private final AtomicInteger wovenCallSites = new AtomicInteger();

	ServiceLoaderWeavingHook(Tracing trace, Technique technique) {
		this.trace = trace;
		this.technique = technique;
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
			byte[] woven = weave(wovenClass.getClassName(), bytes, wovenClass.getBundleWiring());
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

	/**
	 * @param className binary name of the class, for tracing
	 * @param wiring the wiring of the bundle the class belongs to; used to read
	 *            class files for stack map generation, or {@code null}
	 * @return the woven class file, or {@code null} if nothing was changed
	 */
	byte[] weave(String className, byte[] bytes, BundleWiring wiring) {
		if (technique == Technique.CPOOL) {
			ConstantPoolPatcher.Result result = ConstantPoolPatcher.redirect(bytes, SERVICE_LOADER, "load",
				LOAD_DESCRIPTORS, SERVICE_LOADERS);
			if (result == null) {
				return null;
			}
			wovenCallSites.addAndGet(result.redirected());
			trace.trace("woven %s: %d ServiceLoader.load method reference(s) in the constant pool%s",
				className, result.redirected(), wiring == null ? "" : " of bundle " + wiring.getBundle().getSymbolicName());
			return result.bytes();
		}
		ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchyResolver(wiring)));
		ClassModel model = classFile.parse(bytes);
		if (!callsServiceLoaderLoad(model)) {
			return null;
		}
		ClassDesc thisClass = model.thisClass().asSymbol();
		int[] callSites = { 0 };
		CodeTransform redirect = (CodeBuilder builder, CodeElement element) -> {
			if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESTATIC
				&& invoke.owner().name().equalsString(SERVICE_LOADER) && invoke.name().equalsString("load")) {
				MethodTypeDesc type = invoke.typeSymbol();
				if (type.equals(LOAD_TYPE)) {
					builder.ldc(thisClass);
					builder.invokestatic(CD_ServiceLoaders, "load", WOVEN_TYPE);
					callSites[0]++;
					return;
				}
				if (type.equals(LOAD_TYPE_LOADER)) {
					builder.ldc(thisClass);
					builder.invokestatic(CD_ServiceLoaders, "load", WOVEN_TYPE_LOADER);
					callSites[0]++;
					return;
				}
			}
			if (element instanceof InvokeDynamicInstruction indy && redirectMethodReference(builder, indy, thisClass)) {
				callSites[0]++;
				return;
			}
			builder.with(element);
		};
		byte[] woven = classFile.transformClass(model, ClassTransform.transformingMethodBodies(redirect));
		if (callSites[0] == 0) {
			return null;
		}
		wovenCallSites.addAndGet(callSites[0]);
		trace.trace("woven %s: %d ServiceLoader.load call site(s)%s", thisClass.displayName(), callSites[0],
			wiring == null ? "" : " in bundle " + wiring.getBundle().getSymbolicName());
		return woven;
	}

	/**
	 * {@code ServiceLoader::load} as LambdaMetafactory bootstrap argument. Only
	 * call sites without captured arguments are handled: the caller becomes the
	 * single captured argument, i.e. the leading parameter of {@code loadFrom}.
	 *
	 * @return true if the instruction was replaced
	 */
	private boolean redirectMethodReference(CodeBuilder builder, InvokeDynamicInstruction indy, ClassDesc thisClass) {
		DirectMethodHandleDesc bootstrap = indy.bootstrapMethod();
		if (!bootstrap.owner().descriptorString().equals(LAMBDA_METAFACTORY)) {
			return false;
		}
		List<ConstantDesc> args = new ArrayList<>(indy.bootstrapArgs());
		if (args.size() < 3 || !(args.get(1) instanceof DirectMethodHandleDesc impl)
			|| impl.refKind() != MethodHandleInfo.REF_invokeStatic
			|| !impl.owner().descriptorString().equals("L" + SERVICE_LOADER + ";") || !impl.methodName().equals("load")) {
			return false;
		}
		MethodTypeDesc implType = impl.invocationType();
		DirectMethodHandleDesc replacement;
		if (implType.equals(LOAD_TYPE)) {
			replacement = MH_LOAD_FROM;
		} else if (implType.equals(LOAD_TYPE_LOADER)) {
			replacement = MH_LOAD_FROM_LOADER;
		} else {
			return false;
		}
		MethodTypeDesc callSiteType = indy.typeSymbol();
		if (callSiteType.parameterCount() != 0) {
			trace.trace("method reference ServiceLoader::load with captured arguments in %s left unwoven",
				thisClass.displayName());
			return false;
		}
		args.set(1, replacement);
		builder.ldc(thisClass);
		builder.invokedynamic(DynamicCallSiteDesc.of(bootstrap, indy.name().stringValue(),
			callSiteType.insertParameterTypes(0, CD_Class), args.toArray(ConstantDesc[]::new)));
		return true;
	}

	/** constant pool pre filter: a MethodRef java/util/ServiceLoader.load */
	private static boolean callsServiceLoaderLoad(ClassModel model) {
		for (PoolEntry entry : model.constantPool()) {
			if (entry instanceof MethodRefEntry ref && ref.owner().name().equalsString(SERVICE_LOADER)
				&& ref.name().equalsString("load")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Stack maps of a changed method are regenerated; merging frames may need
	 * super types of classes the bundle sees. Read them as resources through the
	 * bundle class loader (no class is defined or initialised by that), fall back
	 * to the JDK's default resolver.
	 */
	private static ClassHierarchyResolver hierarchyResolver(BundleWiring wiring) {
		ClassLoader loader = wiring == null ? null : wiring.getClassLoader();
		ClassHierarchyResolver defaults = ClassHierarchyResolver.defaultResolver();
		if (loader == null) {
			return defaults;
		}
		return ClassHierarchyResolver.ofResourceParsing(loader).orElse(defaults).cached();
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
