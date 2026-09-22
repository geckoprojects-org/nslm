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
package org.example.serviceloader.pitfalls;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.Supplier;

import org.example.serviceloader.api.Greeter;
import org.osgi.service.component.annotations.Component;

/**
 * {@code ServiceLoader.load(Greeter.class)} for Greeter 2.0 in the forms that
 * make it hard to tell who is calling, selected by name:
 * <ul>
 * <li>{@code direct}</li>
 * <li>{@code lambda}: inside a lambda (a synthetic method of this class)</li>
 * <li>{@code methodReference}: {@code ServiceLoader::load}, applied here</li>
 * <li>{@code methodReferenceAppliedByJdk}: {@code ServiceLoader::load} applied
 * by {@code Optional.map}, so the frame below the call is JDK code</li>
 * <li>{@code methodHandle}: looked up by name, not visible in the constant pool</li>
 * <li>{@code reflection}: {@code Method.invoke}, not visible in the constant pool</li>
 * <li>{@code explicitOwnLoader}: {@code load(type, this bundle's class loader)}</li>
 * <li>{@code explicitApiLoader}: {@code load(type, Greeter.class.getClassLoader())},
 * the class loader of the API bundle, as slf4j does it</li>
 * </ul>
 */
@Component(service = Function.class, property = "pitfall=styles")
public class PitfallCallStyles implements Function<String, List<String>> {

	@Override
	public List<String> apply(String style) {
		try {
			return Greeters.names(load(style));
		} catch (InvocationTargetException e) {
			return List.of("error: " + e.getCause());
		} catch (Throwable e) {
			return List.of("error: " + e);
		}
	}

	@SuppressWarnings("unchecked")
	private static ServiceLoader<Greeter> load(String style) throws Throwable {
		switch (style) {
			case "direct":
				return ServiceLoader.load(Greeter.class);
			case "lambda": {
				Supplier<ServiceLoader<Greeter>> supplier = () -> ServiceLoader.load(Greeter.class);
				return supplier.get();
			}
			case "methodReference": {
				Function<Class<Greeter>, ServiceLoader<Greeter>> load = ServiceLoader::load;
				return load.apply(Greeter.class);
			}
			case "methodReferenceAppliedByJdk":
				return Optional.of(Greeter.class).map(ServiceLoader::load).orElseThrow();
			case "methodHandle": {
				MethodHandle load = MethodHandles.lookup()
					.findStatic(ServiceLoader.class, "load", MethodType.methodType(ServiceLoader.class, Class.class));
				return (ServiceLoader<Greeter>) load.invoke(Greeter.class);
			}
			case "reflection":
				return (ServiceLoader<Greeter>) ServiceLoader.class.getMethod("load", Class.class).invoke(null, Greeter.class);
			case "explicitOwnLoader":
				return ServiceLoader.load(Greeter.class, PitfallCallStyles.class.getClassLoader());
			case "explicitApiLoader":
				return ServiceLoader.load(Greeter.class, Greeter.class.getClassLoader());
			default:
				throw new IllegalArgumentException(style);
		}
	}
}
