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

import org.eclipse.osgi.internal.hookregistry.HookConfigurator;
import org.eclipse.osgi.internal.hookregistry.HookRegistry;
import org.example.spi.core.SpiRegistry;
import org.example.spi.core.Tracing;

/** instantiated by Equinox via Class.forName, hence the static hand-over */
public class SpiHookConfigurator implements HookConfigurator {

	static volatile SpiRegistry registry;
	static volatile Tracing trace = Tracing.OFF;

	@Override
	public void addHooks(HookRegistry hookRegistry) {
		if (registry == null) {
			trace.trace("Equinox: no registry, hook not installed");
			return;
		}
		hookRegistry.addClassLoaderHook(new SpiClassLoaderHook(registry, trace));
		trace.trace("Equinox: ClassLoaderHook installed");
	}
}
