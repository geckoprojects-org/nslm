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

/**
 * Minimal tracing to System.err, enabled with a -runproperties flag such as
 * {@code spi.mediator.trace=true} or {@code spi.weaver.trace=true}.
 */
@FunctionalInterface
public interface Tracing {

	Tracing OFF = (f, a) -> {
	};

	void trace(String format, Object... args);

	/** @param prefix name of the component, printed as {@code # <prefix>: } in front of every line */
	static Tracing of(String prefix, boolean enabled) {
		if (!enabled) {
			return OFF;
		}
		String lead = "# " + prefix + ": ";
		return (f, a) -> System.err.println(lead + String.format(f, a));
	}
}
