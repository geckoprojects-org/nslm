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
package org.example.spi.mediator.felix;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import org.example.spi.core.BootDelegation;
import org.example.spi.core.SpiClassLoader;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * Felix: {@code felix.bootdelegation.classloaders} is a {@code Map<Bundle,
 * ClassLoader>} in the framework configuration. Felix asks the loader for a
 * bundle first for every class and resource request (BundleWiringImpl
 * shouldBootDelegate) and continues its normal search when the loader fails.
 * The map is consulted once per bundle when its wiring is created, so a Map
 * whose {@code get} answers for every bundle is enough.
 */
public final class FelixAdapter {

	public static final String BOOT_CLASSLOADERS = "felix.bootdelegation.classloaders";

	private FelixAdapter() {
	}

	public static void configure(Map<String, Object> configuration, SpiRegistry registry, Tracing trace) {
		Object bootDelegation = configuration.get(Constants.FRAMEWORK_BOOTDELEGATION);
		BootDelegation delegation = new BootDelegation(bootDelegation == null ? null : bootDelegation.toString());
		ClassLoader boot = ClassLoader.getPlatformClassLoader(); // Felix' default boot loader on Java 9+
		configuration.put(BOOT_CLASSLOADERS, new AbstractMap<Bundle, ClassLoader>() {
			@Override
			public ClassLoader get(Object key) {
				if (key instanceof Bundle bundle && bundle.getBundleId() != 0) {
					return new SpiClassLoader(registry, bundle, delegation, boot, trace);
				}
				return null;
			}

			@Override
			public Set<Entry<Bundle, ClassLoader>> entrySet() {
				return Set.of();
			}
		});
		trace.trace("Felix: %s installed", BOOT_CLASSLOADERS);
	}
}
