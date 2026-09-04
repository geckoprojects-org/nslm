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
package org.example.spi.core;

import java.net.URL;

import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;

/**
 * One line of a META-INF/services file (or one module-info provides clause) of
 * one bundle.
 *
 * @param serviceName fully qualified name of the service type
 * @param implName fully qualified name of the provider class
 * @param bundle the bundle that contains the provider class (host bundle for
 *            fragments)
 * @param apiCapability the osgi.wiring.package capability the bundle is wired
 *            to for the service type's package; {@code null} if the package is
 *            neither imported nor exported by the bundle (private copy or
 *            boot delegation)
 * @param source the META-INF/services file (bundle entry URL, host or fragment)
 *            this entry was read from; {@code null} if it comes from
 *            module-info {@code provides}
 */
public record ProviderEntry(String serviceName, String implName, Bundle bundle, BundleCapability apiCapability, URL source) {

	String servicePackage() {
		return SpiRegistry.packageOf(serviceName);
	}
}
