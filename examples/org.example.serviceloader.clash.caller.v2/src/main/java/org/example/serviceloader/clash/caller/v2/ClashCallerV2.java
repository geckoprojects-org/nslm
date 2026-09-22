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
package org.example.serviceloader.clash.caller.v2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;

import org.example.serviceloader.api.Greeter;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Component;

/**
 * Calls {@code ServiceLoader.load(Greeter.class)} for Greeter 2.0, without a class
 * loader argument, so the JDK uses the thread context class loader. The argument
 * selects the TCCL: {@code "inherited"} keeps the one the calling thread has,
 * {@code "bundle"} sets this bundle's own class loader for the duration of the
 * call. Returns one line per provider ({@code class|bundle symbolic name|bundle id|bundle state})
 * or the message of a {@link ServiceConfigurationError}.
 */
@Component(service = Function.class, property = "clash.api=2.0")
public class ClashCallerV2 implements Function<String, List<String>> {

	@Override
	public List<String> apply(String tccl) {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		if ("bundle".equals(tccl)) {
			thread.setContextClassLoader(ClashCallerV2.class.getClassLoader());
		}
		try {
			List<String> result = new ArrayList<>();
			Iterator<Greeter> greeters = ServiceLoader.load(Greeter.class).iterator();
			// a ServiceConfigurationError skips the offending provider; bounded in case it does not
			for (int i = 0; i < 20; i++) {
				try {
					if (!greeters.hasNext()) {
						break;
					}
					Class<?> type = greeters.next().getClass();
					Bundle bundle = FrameworkUtil.getBundle(type);
					result.add(type.getName() + "|" + bundle.getSymbolicName() + "|" + bundle.getBundleId() + "|"
						+ bundle.getState());
				} catch (ServiceConfigurationError e) {
					result.add("ServiceConfigurationError: " + e.getMessage());
				}
			}
			return result;
		} finally {
			thread.setContextClassLoader(previous);
		}
	}
}
