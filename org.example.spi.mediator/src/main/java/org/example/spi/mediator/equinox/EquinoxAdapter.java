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
package org.example.spi.mediator.equinox;

import java.util.Map;

import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;

/**
 * Equinox: bundle class loaders are hooked with a {@code ClassLoaderHook}
 * (BundleLoader.findResources/findClass call pre/post hooks). Hooks are
 * registered by a {@code HookConfigurator} that Equinox instantiates by name
 * ({@code osgi.hook.configurators.include}) with the framework class loader,
 * which is the -runpath loader this jar lives in.
 */
public final class EquinoxAdapter {

	public static final String HOOK_CONFIGURATORS_INCLUDE = "osgi.hook.configurators.include";

	private EquinoxAdapter() {
	}

	public static void configure(Map<String, Object> configuration, SpiRegistry registry, Tracing trace) {
		SpiHookConfigurator.registry = registry;
		SpiHookConfigurator.trace = trace;
		Object existing = configuration.get(HOOK_CONFIGURATORS_INCLUDE);
		String name = SpiHookConfigurator.class.getName();
		configuration.put(HOOK_CONFIGURATORS_INCLUDE,
			existing == null || existing.toString().isBlank() ? name : existing + "," + name);
		trace.trace("Equinox: %s=%s", HOOK_CONFIGURATORS_INCLUDE, configuration.get(HOOK_CONFIGURATORS_INCLUDE));
	}
}
